package uk.ac.ebi.tsc.tesk.authz;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks an OpenID AuthZEN 1.0 Access Evaluation endpoint ({@code POST}, JSON) for one decision. One
 * attempt, no retries, bounded by the timeout as a whole. The answer is a decision only if the
 * status is 200 and the body is a JSON object whose {@code decision} is a boolean; anything else is
 * a failure ({@link Failure}). Sends the server-generated request ID as {@code X-Request-ID}, and,
 * if a credential file is set, its content as a bearer credential (read on each call, so that a
 * rotated file is picked up); never the caller's access token.
 */
public class DecisionServiceClient {

    /** Why the decision service gave no decision (logged; the client gets a generic 503) */
    public static class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        Failure(String cause) {
            super(cause);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI url;
    private final Duration timeout;
    private final Path credentialFile;
    private final HttpClient client;

    /**
     * @param url the evaluation endpoint
     * @param timeout the time a call may take as a whole
     * @param credentialFile the file with the bearer credential, or null for none
     */
    public DecisionServiceClient(URI url, Duration timeout, Path credentialFile) {
        this.url = url;
        this.timeout = timeout;
        this.credentialFile = credentialFile;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Reads the credential file: its content without surrounding white space
     *
     * @throws IOException if it can't be read, is empty, or has control characters (e.g. several
     *     lines), which can't be sent in a header
     */
    static String readCredential(Path file) throws IOException {
        String credential = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
        if (credential.isEmpty()) {
            throw new IOException("empty");
        }
        if (credential.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IOException("control characters");
        }
        return credential;
    }

    /**
     * The decision for an evaluation request
     *
     * @param body the AuthZEN evaluation request
     * @param requestId the request ID, new for each evaluation
     * @throws Failure if the decision service gave no decision
     */
    public boolean evaluate(Map<String, Object> body, String requestId) throws Failure {
        HttpRequest built;
        try {
            built = request(body, requestId);
        } catch (RuntimeException e) {
            throw new Failure("request not built: " + e.getClass().getSimpleName());
        }
        return decision(built);
    }

    private HttpRequest request(Map<String, Object> body, String requestId) throws Failure {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(this.url)
                        .timeout(this.timeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("X-Request-ID", requestId)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        if (this.credentialFile != null) {
            try {
                request.header("Authorization", "Bearer " + readCredential(this.credentialFile));
            } catch (IOException e) {
                throw new Failure("credential file unreadable");
            }
        }
        return request.build();
    }

    private boolean decision(HttpRequest request) throws Failure {
        CompletableFuture<HttpResponse<String>> call =
                this.client.sendAsync(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = call.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new Failure("timeout");
        } catch (ExecutionException e) {
            throw new Failure(e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted");
        }
        if (response.statusCode() != 200) {
            throw new Failure("status " + response.statusCode());
        }
        JsonNode answer;
        try {
            answer = JSON.readTree(response.body());
        } catch (JacksonException e) {
            throw new Failure("body not JSON");
        }
        JsonNode decision = answer.get("decision");
        if (!answer.isObject() || decision == null || !decision.isBoolean()) {
            throw new Failure("no boolean decision");
        }
        return decision.booleanValue();
    }
}
