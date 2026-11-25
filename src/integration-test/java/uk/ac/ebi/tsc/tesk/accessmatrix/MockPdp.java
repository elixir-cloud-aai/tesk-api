package uk.ac.ebi.tsc.tesk.accessmatrix;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A mock decision service (AuthZEN evaluation endpoint, tes-authz): a stand-in for the reference
 * policy of tes-authz, in Java, for TESK's integration tests, with failure modes. It records the
 * requests, so that tests can check what TESK asked.
 *
 * <p>The reference policy (owner-only, D-388, D-407, D-409 Q3 b): {@code CreateTask} is allowed if
 * the subject's entitlements hold {@code elixir:ECP_CLN:EOC-Compute} exactly, or if the subject is
 * a listed service client ({@code sub} = {@code client_id}, listed for the issuer); {@code GetTask}
 * and {@code CancelTask} if the task's owner is the subject; {@code ListTasks} answers the scope
 * {@code own}. The request fields it reads are those of the tes-authz contract that won't change
 * (subject, action, resource owner); T4a aligns the rest (e.g. where the list scope is answered)
 * with the contract of tes-authz slice Z1.
 */
final class MockPdp implements ResponseDefinitionTransformerV2 {

    static final int PORT = 8093;
    static final String PATH = "/access/v1/evaluation";
    static final String URL = "http://localhost:" + PORT + PATH;

    /** How the decision service answers */
    enum Mode {
        /** the reference policy */
        REFERENCE,
        /** connections are reset */
        DOWN,
        /** it answers after the timeout of TESK's client (1 s in the tests) */
        TIMEOUT,
        /** it answers 200 with a body that isn't JSON */
        BAD_ANSWER,
        /** the reference policy, but list answers the scope {@code all} */
        LIST_ALL,
        /** the reference policy, but list answers the scope {@code none} */
        LIST_NONE,
        /** the reference policy, but get and cancel are allowed for any task (it widens) */
        WIDEN
    }

    private volatile Mode mode = Mode.REFERENCE;

    private final List<JsonObject> requests = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String getName() {
        return "reference-policy";
    }

    void reset() {
        this.mode = Mode.REFERENCE;
        this.requests.clear();
    }

    void mode(Mode mode) {
        this.mode = mode;
    }

    /** The bodies of the evaluation requests so far */
    List<JsonObject> requests() {
        synchronized (this.requests) {
            return new ArrayList<>(this.requests);
        }
    }

    /** The requests so far for this action (e.g. {@code CreateTask}) */
    List<JsonObject> requests(String action) {
        return requests().stream()
                .filter(request -> action.equals(string(request, "action", "name").orElse(null)))
                .toList();
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        JsonObject request;
        try {
            request =
                    JsonParser.parseString(serveEvent.getRequest().getBodyAsString())
                            .getAsJsonObject();
        } catch (RuntimeException e) {
            return new ResponseDefinitionBuilder().withStatus(400).build();
        }
        this.requests.add(request);
        return switch (this.mode) {
            case DOWN ->
                    new ResponseDefinitionBuilder()
                            .withFault(Fault.CONNECTION_RESET_BY_PEER)
                            .build();
            case TIMEOUT -> answer(decide(request)).withFixedDelay(3000).build();
            case BAD_ANSWER ->
                    new ResponseDefinitionBuilder()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withBody("<html>Bad Gateway</html>")
                            .build();
            default -> answer(decide(request)).build();
        };
    }

    private static ResponseDefinitionBuilder answer(JsonObject decision) {
        return new ResponseDefinitionBuilder()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(decision.toString());
    }

    private JsonObject decide(JsonObject request) {
        String action = string(request, "action", "name").orElse("");
        JsonObject decision = new JsonObject();
        switch (action) {
            case "CreateTask" -> decision.addProperty("decision", mayCreate(request));
            case "GetTask", "CancelTask" ->
                    decision.addProperty("decision", this.mode == Mode.WIDEN || isOwner(request));
            case "ListTasks" -> {
                decision.addProperty("decision", true);
                JsonObject context = new JsonObject();
                context.addProperty(
                        "scope",
                        switch (this.mode) {
                            case LIST_ALL -> "all";
                            case LIST_NONE -> "none";
                            default -> "own";
                        });
                decision.add("context", context);
            }
            default -> decision.addProperty("decision", false);
        }
        return decision;
    }

    private static boolean isOwner(JsonObject request) {
        Optional<String> owner = string(request, "resource", "properties", "owner");
        return owner.isPresent() && owner.equals(string(request, "subject", "id"));
    }

    private static boolean mayCreate(JsonObject request) {
        Optional<JsonElement> entitlements =
                element(request, "subject", "properties", "claims", "eduperson_entitlement");
        if (entitlements.isPresent() && entitlements.get().isJsonArray()) {
            JsonArray values = entitlements.get().getAsJsonArray();
            for (JsonElement value : values) {
                if (value.isJsonPrimitive()
                        && Entitlements.EOC_COMPUTE.equals(value.getAsString())) {
                    return true;
                }
            }
        }
        // listed service clients (policy data service_clients, rendered from TESK's list)
        Optional<String> sub = string(request, "subject", "properties", "sub");
        return string(request, "subject", "properties", "iss").equals(Optional.of(MockIdp.ISSUER))
                && sub.equals(Optional.of(MockIdp.LISTED_CLIENT))
                && sub.equals(string(request, "subject", "properties", "client_id"));
    }

    private static Optional<String> string(JsonObject object, String... path) {
        return element(object, path)
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString);
    }

    private static Optional<JsonElement> element(JsonObject object, String... path) {
        JsonElement current = object;
        for (String key : path) {
            if (current == null || !current.isJsonObject()) {
                return Optional.empty();
            }
            current = current.getAsJsonObject().get(key);
        }
        return Optional.ofNullable(current);
    }
}
