package uk.ac.ebi.tsc.tesk.config.security.token;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One HTTP call whose time is bounded as a whole (connection, answer and body), for the calls that
 * decide about a caller (the user info endpoint, the decision service): {@link HttpRequest#timeout}
 * alone doesn't bound reading the body. Each caller turns a {@link Failure} into its own error.
 */
public final class BoundedHttp {

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

    /**
     * A client for such calls, with the timeout as connect timeout
     *
     * @param timeout the timeout
     */
    public static HttpClient client(Duration timeout) {
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Sends the request and reads the body as UTF-8, within the timeout; a call that takes longer
     * is cancelled
     *
     * @param client the client
     * @param request the request
     * @param timeout the time the call may take as a whole
     * @return the response, with the body
     * @throws Failure if there is no answer in time, the call fails or the thread is interrupted
     *     (whose interrupt status is kept)
     */
    public static HttpResponse<String> send(
            HttpClient client, HttpRequest request, Duration timeout) throws Failure {
        CompletableFuture<HttpResponse<String>> call =
                client.sendAsync(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new Failure("timeout");
        } catch (ExecutionException e) {
            throw new Failure(e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted");
        }
    }
}
