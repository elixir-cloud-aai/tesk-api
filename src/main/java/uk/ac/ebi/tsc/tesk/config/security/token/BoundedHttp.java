package uk.ac.ebi.tsc.tesk.config.security.token;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One HTTP call whose time is bounded as a whole (connection, answer and body), and whose body is
 * bounded in size, for the calls that decide about a caller (the user info endpoint, the decision
 * service): {@link HttpRequest#timeout} alone doesn't bound reading the body. Each caller turns a
 * {@link Failure} into its own error.
 */
public final class BoundedHttp {

    /** Largest body read, in bytes: far more than any user info or decision */
    static final int MAX_BODY_BYTES = 1 << 20;

    private BoundedHttp() {}

    /** Why a call gave no answer: {@code timeout}, {@code interrupted}, or the cause's class */
    public static final class Failure extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param reason why
         */
        Failure(String reason) {
            super(reason, null, false, false);
        }
    }

    /** A body longer than {@link #MAX_BODY_BYTES} */
    static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("body too large");
        }
    }

    /**
     * Reads a body as UTF-8, at most {@code limit} bytes: a longer one ends the call with {@link
     * BodyTooLargeException} instead of filling the memory (within the timeout of the call)
     */
    static final class LimitedBody implements HttpResponse.BodySubscriber<String> {

        private final HttpResponse.BodySubscriber<String> body =
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private final long limit;
        private long length;
        private boolean exceeded;

        /**
         * @param limit the largest body, in bytes
         */
        LimitedBody(long limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<String> getBody() {
            return this.body.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.body.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (this.exceeded) {
                return;
            }
            for (ByteBuffer item : items) {
                this.length += item.remaining();
            }
            if (this.length > this.limit) {
                // fails the call; the rest is discarded as it arrives (cancelling the subscription
                // would fail the call with the client's own error, without saying why)
                this.exceeded = true;
                this.body.onError(new BodyTooLargeException());
                return;
            }
            this.body.onNext(items);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!this.exceeded) {
                this.body.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!this.exceeded) {
                this.body.onComplete();
            }
        }
    }

    /**
     * A client for such calls, with the timeout as connect timeout
     *
     * @param timeout the timeout
     */
    public static HttpClient client(Duration timeout) {
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Sends the request and reads the body as UTF-8, within the timeout and at most {@link
     * #MAX_BODY_BYTES}; a call that takes longer is cancelled
     *
     * @param client the client
     * @param request the request
     * @param timeout the time the call may take as a whole
     * @return the response, with the body
     * @throws Failure if there is no answer in time, the call fails (also: {@code body too large})
     *     or the thread is interrupted (whose interrupt status is kept)
     */
    public static HttpResponse<String> send(
            HttpClient client, HttpRequest request, Duration timeout) throws Failure {
        CompletableFuture<HttpResponse<String>> call =
                client.sendAsync(request, info -> new LimitedBody(MAX_BODY_BYTES));
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new Failure("timeout");
        } catch (ExecutionException e) {
            // the client wraps errors of the body in an IOException of its own
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof BodyTooLargeException) {
                    throw new Failure("body too large");
                }
            }
            throw new Failure(e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted");
        }
    }
}
