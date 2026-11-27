package uk.ac.ebi.tsc.tesk.config.security.token;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The size bound of the bodies of the calls that decide about a caller */
public class BoundedHttpTest {

    @RegisterExtension
    static WireMockExtension server =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET().build();
    }

    /** A body up to the limit is read whole */
    @Test
    public void send_bodyWithinTheLimit() throws Exception {
        server.stubFor(get(urlEqualTo("/small")).willReturn(ok("{\"decision\": true}")));

        assertThat(BoundedHttp.send(BoundedHttp.client(TIMEOUT), request("/small"), TIMEOUT).body())
                .isEqualTo("{\"decision\": true}");
    }

    /** A longer body ends the call, without reading it whole */
    @Test
    public void send_bodyTooLarge_fails() {
        server.stubFor(
                get(urlEqualTo("/large"))
                        .willReturn(ok("x".repeat(BoundedHttp.MAX_BODY_BYTES + 1))));

        assertThatThrownBy(() ->
                        BoundedHttp.send(BoundedHttp.client(TIMEOUT), request("/large"), TIMEOUT))
                .isInstanceOf(BoundedHttp.Failure.class)
                .hasMessage("body too large");
    }

    /** A subscription that records whether it was cancelled */
    private static final class Recorded implements Flow.Subscription {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void request(long n) {}

        @Override
        public void cancel() {
            this.cancelled.set(true);
        }
    }

    private static List<ByteBuffer> bytes(String text) {
        return List.of(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Past the limit, the body fails, and the rest is discarded as it arrives */
    @Test
    public void limitedBody_pastTheLimit() {
        BoundedHttp.LimitedBody body = new BoundedHttp.LimitedBody(4);
        Recorded subscription = new Recorded();
        body.onSubscribe(subscription);

        body.onNext(bytes("abc"));
        body.onNext(bytes("de"));
        body.onNext(bytes("f"));
        body.onError(new IOException("late"));
        body.onComplete();

        assertThat(subscription.cancelled).isFalse();
        CompletableFuture<String> result = body.getBody().toCompletableFuture();
        assertThatThrownBy(result::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(BoundedHttp.BodyTooLargeException.class);
    }

    /** Errors of the call within the limit are passed on */
    @Test
    public void limitedBody_error() {
        BoundedHttp.LimitedBody body = new BoundedHttp.LimitedBody(4);
        body.onSubscribe(new Recorded());
        IOException error = new IOException("reset");

        body.onError(error);

        assertThatThrownBy(() -> body.getBody().toCompletableFuture().get()).hasCause(error);
    }

    /** A body of exactly the limit is read */
    @Test
    public void limitedBody_atTheLimit() throws Exception {
        BoundedHttp.LimitedBody body = new BoundedHttp.LimitedBody(4);
        Recorded subscription = new Recorded();
        body.onSubscribe(subscription);

        body.onNext(bytes("ab"));
        body.onNext(bytes("cd"));
        body.onComplete();

        assertThat(body.getBody().toCompletableFuture().get()).isEqualTo("abcd");
        assertThat(subscription.cancelled).isFalse();
    }
}
