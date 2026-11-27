package uk.ac.ebi.tsc.tesk.authz;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One AuthZEN evaluation over HTTP: decisions, and every kind of failure */
public class DecisionServiceClientTest {

    @RegisterExtension
    static WireMockExtension pdp =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir Path dir;

    private static final Map<String, Object> BODY = Collections.singletonMap("a", "b");

    private DecisionServiceClient client(Path credentialFile, long timeoutMillis) {
        return new DecisionServiceClient(
                URI.create(pdp.baseUrl() + "/access/v1/evaluation"),
                Duration.ofMillis(timeoutMillis),
                credentialFile);
    }

    private static void answer(int status, String body) {
        pdp.stubFor(
                post(urlEqualTo("/access/v1/evaluation"))
                        .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    @Test
    public void evaluate_true() throws Exception {
        pdp.stubFor(
                post(urlEqualTo("/access/v1/evaluation"))
                        .willReturn(okJson("{\"decision\": true, \"context\": {}}")));

        assertThat(client(null, 2000).evaluate(BODY, "rid-1")).isTrue();
        pdp.verify(
                postRequestedFor(urlEqualTo("/access/v1/evaluation"))
                        .withHeader("X-Request-ID", equalTo("rid-1"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withHeader("Authorization", absent())
                        .withRequestBody(equalToJson("{\"a\": \"b\"}")));
    }

    @Test
    public void evaluate_false() throws Exception {
        answer(200, "{\"decision\": false}");

        assertThat(client(null, 2000).evaluate(BODY, "rid")).isFalse();
    }

    /** The credential file's content, without surrounding white space, as bearer credential */
    @Test
    public void evaluate_withCredential() throws Exception {
        Path file = dir.resolve("credential");
        Files.write(file, " secret-1\n".getBytes(StandardCharsets.UTF_8));
        answer(200, "{\"decision\": true}");

        assertThat(client(file, 2000).evaluate(BODY, "rid")).isTrue();
        pdp.verify(
                postRequestedFor(urlEqualTo("/access/v1/evaluation"))
                        .withHeader("Authorization", equalTo("Bearer secret-1")));
    }

    @Test
    public void evaluate_credentialFileGone() {
        assertThatThrownBy(() -> client(dir.resolve("missing"), 2000).evaluate(BODY, "rid"))
                .isInstanceOf(DecisionServiceClient.Failure.class)
                .hasMessage("credential file empty, unreadable or not printable ASCII");
    }

    /** A credential of several lines, or not ASCII, can't be a bearer header: refused, not a 500 */
    @Test
    public void readCredential_controlCharacters() throws IOException {
        Path file = dir.resolve("lines");
        Files.write(file, "line-1\nline-2\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> DecisionServiceClient.readCredential(file))
                .isInstanceOf(IOException.class);
        Files.write(file, "a\u007fb".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> DecisionServiceClient.readCredential(file))
                .isInstanceOf(IOException.class);
        // not ASCII: the header would carry other bytes than the secret, or not be built
        Files.write(file, "caf\u00e9".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> DecisionServiceClient.readCredential(file))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> client(file, 2000).evaluate(BODY, "rid"))
                .isInstanceOf(DecisionServiceClient.Failure.class)
                .hasMessage("credential file empty, unreadable or not printable ASCII");
    }

    /** A value whose getter fails, so that it can't be written as JSON */
    public static class Unwritable {
        public String getX() {
            throw new IllegalStateException("no");
        }
    }

    /** A request that can't be built (here: a body that can't be written) is a failure */
    @Test
    public void evaluate_requestNotBuilt() {
        assertThatThrownBy(() ->
                        client(null, 2000)
                                .evaluate(
                                        java.util.Collections.singletonMap("x", new Unwritable()),
                                        "rid"))
                .isInstanceOf(DecisionServiceClient.Failure.class)
                .hasMessageStartingWith("request not built");
        assertThatThrownBy(() -> client(null, 2000).evaluate(BODY, "bad\nid"))
                .isInstanceOf(DecisionServiceClient.Failure.class)
                .hasMessage("request not built: IllegalArgumentException");
    }

    @Test
    public void readCredential_empty() throws IOException {
        Path file = dir.resolve("empty");
        Files.write(file, "  \n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> DecisionServiceClient.readCredential(file))
                .isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"decision\": \"true\"}",
                "{}",
                "[true]",
                "true",
                "",
                "{\"decision\": null}"
            })
    public void evaluate_noBooleanDecision(String body) {
        answer(200, body);

        assertThatThrownBy(() -> client(null, 2000).evaluate(BODY, "rid"))
                .isInstanceOf(DecisionServiceClient.Failure.class);
    }

    @Test
    public void evaluate_notJson() {
        answer(200, "decision: true");

        assertThatThrownBy(() -> client(null, 2000).evaluate(BODY, "rid"))
                .isInstanceOf(DecisionServiceClient.Failure.class)
                .hasMessage("body not JSON");
    }

    @Test
    public void evaluate_status() {
        answer(500, "{\"decision\": true}");

        assertThatThrownBy(() -> client(null, 2000).evaluate(BODY, "rid"))
                .isInstanceOf(DecisionServiceClient.Failure.class)
                .hasMessage("status 500");
    }

    @Test
    public void evaluate_timeout() {
        pdp.stubFor(
                post(urlEqualTo("/access/v1/evaluation"))
                        .willReturn(okJson("{\"decision\": true}").withFixedDelay(1000)));

        assertThatThrownBy(() -> client(null, 100).evaluate(BODY, "rid"))
                .isInstanceOf(DecisionServiceClient.Failure.class)
                .hasMessage("timeout");
    }

    @Test
    public void evaluate_connectionRefused() {
        DecisionServiceClient client =
                new DecisionServiceClient(
                        URI.create("http://127.0.0.1:1/access/v1/evaluation"),
                        Duration.ofSeconds(2),
                        null);

        assertThatThrownBy(() -> client.evaluate(BODY, "rid"))
                .isInstanceOf(DecisionServiceClient.Failure.class)
                .hasMessage("ConnectException");
    }

    @Test
    public void evaluate_interrupted() {
        pdp.stubFor(
                post(urlEqualTo("/access/v1/evaluation"))
                        .willReturn(okJson("{\"decision\": true}").withFixedDelay(1000)));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client(null, 2000).evaluate(BODY, "rid"))
                    .isInstanceOf(DecisionServiceClient.Failure.class)
                    .hasMessage("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
