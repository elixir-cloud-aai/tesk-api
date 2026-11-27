package uk.ac.ebi.tsc.tesk.accessmatrix;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import uk.ac.ebi.tsc.tesk.accessmatrix.Callers.Caller;
import uk.ac.ebi.tsc.tesk.accessmatrix.Matrix.Operation;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * The access matrix of the redesign (design section 2) in the default configuration, and the
 * variants that the design adds to its rows: the stamped owner and actor on create, missing and
 * foreign tasks alike, page tokens of other owners, the user info endpoint refusing a token that
 * TESK validated itself, and which operations ask the decision service. The pending cells are in
 * {@code access-matrix/pending.csv} (configuration {@code default}).
 */
class AccessMatrixIT extends MatrixHarness {

    /** The mock Kubernetes API (nested, so that it replaces the application's client) */
    @TestConfiguration
    static class Kubernetes extends KubernetesClientMock {}

    @Override
    String config() {
        return "default";
    }

    /** Row 3/14 variant: create stamps the owner {@code <iss>|<sub>} (label and annotation) */
    @Test
    void create_stampsIssuerAndSubject() throws Exception {
        Caller caller = Callers.byId("3");
        String token = prepare(caller);
        new MockCluster(kubernetes).stub();
        MvcResult result = perform(Operation.CREATE, token, null);
        String owner = Owners.target(MockIdp.ISSUER, caller.sub());
        check("stamp-owner", "CREATE", result, "owner " + owner, r -> assertStamped(owner));
    }

    /** Row 14 variant: an exchanged token's task is the subject's, with the actor recorded */
    @Test
    void create_exchanged_stampsSubjectAndActor() throws Exception {
        Caller caller = Callers.byId("14");
        String token = prepare(caller);
        new MockCluster(kubernetes).stub();
        MvcResult result = perform(Operation.CREATE, token, null);
        String owner = Owners.target(MockIdp.ISSUER, caller.sub());
        check(
                "stamp-actor",
                "CREATE",
                result,
                "owner " + owner + ", actor " + MockIdp.TRUSTED_ACTOR,
                r -> {
                    assertStamped(owner);
                    // the actor chain is recorded in an annotation of its own (its name: slice
                    // T6), as JSON with the actor's sub (design section 10, R2)
                    Map<String, String> annotations = createdJobAnnotations();
                    assertTrue(
                            annotations.values().stream().anyMatch(AccessMatrixIT::isActorChain),
                            "an annotation records the actor chain: " + annotations);
                });
    }

    /** Whether the value is JSON that holds the trusted actor as {@code sub} of a hop */
    private static boolean isActorChain(String value) {
        JsonElement json;
        try {
            json = JsonParser.parseString(value);
        } catch (RuntimeException e) {
            return false;
        }
        return json.toString().contains("\"sub\":\"" + MockIdp.TRUSTED_ACTOR + "\"");
    }

    private void assertStamped(String owner) {
        Map<String, String> annotations = createdJobAnnotations();
        assertEquals(owner, annotations.get("creator-user-id"), "annotation creator-user-id");
        JsonObject labels = createdJob().getAsJsonObject("metadata").getAsJsonObject("labels");
        assertEquals(
                CreatorLabels.hash(owner),
                labels.get("creator-user-hash").getAsString(),
                "label creator-user-hash");
    }

    /** The job that TESK created (the last POST of a job, not a dry run) */
    private JsonObject createdJob() {
        List<ServeEvent> posts =
                kubernetes.getAllServeEvents().stream()
                        .filter(e -> e.getRequest().getMethod().getName().equals("POST"))
                        .filter(e -> e.getRequest().getUrl().startsWith(MockCluster.JOBS))
                        .filter(e -> !e.getRequest().getUrl().contains("dryRun"))
                        .toList();
        assertEquals(1, posts.size(), "jobs created");
        return JsonParser.parseString(posts.get(0).getRequest().getBodyAsString())
                .getAsJsonObject();
    }

    private Map<String, String> createdJobAnnotations() {
        JsonObject annotations =
                createdJob().getAsJsonObject("metadata").getAsJsonObject("annotations");
        return annotations.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getAsString()));
    }

    /** Row 5 variant: cancel of a missing ID is 404, as of a foreign task */
    @Test
    void cancel_missing() throws Exception {
        Caller caller = Callers.byId("5");
        String token = prepare(caller);
        new MockCluster(kubernetes).task(OWN, caller.owner(), caller.team()).stub();
        MvcResult result = perform(Operation.CANCEL, token, MISSING);
        check("missing", "CANCEL", result, "404", cell("404", Operation.CANCEL, MISSING));
    }

    /**
     * Row 5 variant: a foreign task takes the same path as a missing ID (the same Kubernetes
     * requests), so that answers and timing are no oracle of other users' tasks
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = Operation.class,
            names = {"GET_FULL", "CANCEL"})
    void foreignAndMissing_sameRequests(Operation operation) throws Exception {
        Caller caller = Callers.byId("5");
        String token = prepare(caller);
        new MockCluster(kubernetes)
                .task(FOREIGN, Owners.of(MockIdp.ISSUER, Callers.SOMEONE_ELSE), Callers.TEAM)
                .stub();
        MvcResult foreign = perform(operation, token, FOREIGN);
        List<String> foreignRequests = requests(FOREIGN);
        kubernetes.resetRequests();
        MvcResult missing = perform(operation, token, MISSING);
        List<String> missingRequests = requests(MISSING);
        check(
                "same-requests",
                operation.column,
                foreign,
                "404 with the requests of a missing ID " + missingRequests,
                r -> {
                    assertEquals(404, missing.getResponse().getStatus(), "missing ID");
                    assertEquals(404, r.getResponse().getStatus(), "foreign task");
                    assertEquals(missingRequests, foreignRequests, "Kubernetes requests");
                });
    }

    /** The Kubernetes requests so far (method and URL), the task's name replaced by {@code ID} */
    private List<String> requests(String taskId) {
        List<ServeEvent> events = new ArrayList<>(kubernetes.getAllServeEvents());
        // the journal lists the newest first
        Collections.reverse(events);
        return events.stream()
                .map(e -> e.getRequest().getMethod() + " " + e.getRequest().getUrl())
                .map(request -> request.replace(taskId, "ID"))
                .toList();
    }

    /**
     * Page tokens are bound to the owner's selector: a token of another owner's list is refused by
     * Kubernetes, which TESK answers with 400, and never lists another owner's tasks
     */
    @Test
    void list_pageTokenOfAnotherOwner() throws Exception {
        Caller caller = Callers.byId("3");
        String token = prepare(caller);
        new MockCluster(kubernetes).task(OWN, caller.owner(), caller.team()).stub();
        // Kubernetes refuses a continue token with another selector than it was issued for
        kubernetes.givenThat(
                get(urlPathEqualTo(MockCluster.JOBS))
                        .withQueryParam("continue", equalTo("token-of-someone-else"))
                        .atPriority(0)
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"kind\":\"Status\",\"status\":\"Failure\","
                                                        + "\"message\":\"continue key is not"
                                                        + " valid\",\"reason\":\"BadRequest\","
                                                        + "\"code\":400}")));
        MvcResult result =
                this.mvc
                        .perform(
                                MockMvcRequestBuilders.get(
                                                TASKS + "?page_token=token-of-someone-else")
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andReturn();
        check(
                "page-token",
                "LIST",
                result,
                "400, with the caller's selector",
                r -> {
                    assertEquals(400, r.getResponse().getStatus(), "status");
                    kubernetes.verify(
                            getRequestedFor(urlPathEqualTo(MockCluster.JOBS))
                                    .withQueryParam("continue", equalTo("token-of-someone-else"))
                                    .withQueryParam(
                                            "labelSelector",
                                            matching(
                                                    ".*creator-user-hash="
                                                            + CreatorLabels.hash(caller.owner())
                                                            + ".*")));
                });
    }

    /**
     * Row 19 note: a token that TESK validates itself, which the user info endpoint refuses (401 or
     * 403): create is 401, as the entitlements can't be looked up; the other operations don't look
     * them up. The user info endpoint is only the source of entitlements for create (T4a):
     * authentication never asks it
     */
    static Stream<Arguments> refusals() {
        return Stream.of(401, 403)
                .flatMap(refusal ->
                        Stream.of(Operation.values())
                                .map(operation -> Arguments.of(refusal, operation)));
    }

    @ParameterizedTest(name = "user info {0}, {1}")
    @MethodSource("refusals")
    void userInfoRefusesToken(int refusal, Operation operation) throws Exception {
        Caller caller = Callers.byId("19a");
        String token =
                MockIdp.sign(MockIdp.userClaims(caller.sub()), MockIdp.Signature.VALID, "at+jwt");
        MockIdp.stubUserInfo(idp, MockIdp.answered(token, refusal));
        new MockCluster(kubernetes).task(OWN, caller.owner(), caller.team()).stub();
        MvcResult result = perform(operation, token, OWN);
        String expected =
                switch (operation) {
                    case CREATE -> "401";
                    case LIST -> "200o";
                    default -> "200";
                };
        Target target = cell(expected, operation, OWN);
        check(
                "userinfo-" + refusal,
                operation.column,
                result,
                expected + (operation == Operation.CREATE ? "" : ", no user info request"),
                r -> {
                    target.check(r);
                    // only create looks up the entitlements
                    if (operation != Operation.CREATE) {
                        idp.verify(0, getRequestedFor(urlPathEqualTo("/oidc/userinfo")));
                    }
                });
    }

    /**
     * Row 9 note: a real LS Login client token of an unlisted client has the client's own ID as its
     * audience, which TESK accepts for listed clients only, so it is refused on every operation
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Operation.class, names = "SI", mode = EnumSource.Mode.EXCLUDE)
    void unlistedClient_withItsOwnAudience_isRefused(Operation operation) throws Exception {
        JwtClaims claims = MockIdp.clientClaims(MockIdp.UNLISTED_CLIENT);
        claims.setAudience(MockIdp.UNLISTED_CLIENT);
        String token = MockIdp.sign(claims, MockIdp.Signature.VALID, "at+jwt");
        // LS Login's user info endpoint answers client tokens with 403
        MockIdp.stubUserInfo(idp, MockIdp.answered(token, 403));
        new MockCluster(kubernetes)
                .task(FOREIGN, Owners.of(MockIdp.ISSUER, Callers.SOMEONE_ELSE), Callers.TEAM)
                .stub();
        MvcResult result = perform(operation, token, FOREIGN);
        check("9-own-audience", operation.column, result, "401", cell("401", operation, FOREIGN));
    }

    /**
     * Row 3, D-407 default: create asks the decision service once, with the subject (owner ID,
     * issuer, subject, entitlements) and the caller's set of tasks
     */
    @Test
    void create_asksDecisionService() throws Exception {
        Caller caller = Callers.byId("3");
        String token = prepare(caller);
        new MockCluster(kubernetes).stub();
        MvcResult result = perform(Operation.CREATE, token, null);
        String owner = Owners.target(MockIdp.ISSUER, caller.sub());
        check(
                "asks-pdp",
                "CREATE",
                result,
                "one CreateTask evaluation of " + owner,
                r -> {
                    assertEquals(200, r.getResponse().getStatus(), "status");
                    List<JsonObject> requests = PDP.requests();
                    assertEquals(1, requests.size(), "evaluations");
                    JsonObject request = requests.get(0);
                    assertEquals(
                            "CreateTask",
                            request.getAsJsonObject("action").get("name").getAsString());
                    JsonObject subject = request.getAsJsonObject("subject");
                    assertEquals(owner, subject.get("id").getAsString(), "subject.id");
                    JsonObject properties = subject.getAsJsonObject("properties");
                    assertEquals(MockIdp.ISSUER, properties.get("iss").getAsString(), "iss");
                    assertEquals(caller.sub(), properties.get("sub").getAsString(), "sub");
                    boolean entitled = false;
                    for (JsonElement value :
                            properties
                                    .getAsJsonObject("claims")
                                    .getAsJsonArray("eduperson_entitlement")) {
                        entitled |= Entitlements.EOC_COMPUTE.equals(value.getAsString());
                    }
                    assertTrue(entitled, "entitlements sent");
                    JsonObject resource = request.getAsJsonObject("resource");
                    assertEquals("tes_task_set", resource.get("type").getAsString());
                    assertEquals("own", resource.get("id").getAsString());
                });
    }

    /** D-407 default: get, list and cancel are TESK's decisions, without the decision service */
    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = Operation.class,
            names = {"GET_MINIMAL", "GET_BASIC", "GET_FULL", "LIST", "CANCEL"})
    void getListCancel_askNoDecisionService(Operation operation) throws Exception {
        Caller caller = Callers.byId("3");
        String token = prepare(caller);
        new MockCluster(kubernetes).task(OWN, caller.owner(), caller.team()).stub();
        MvcResult result = perform(operation, token, OWN);
        check(
                "no-pdp",
                operation.column,
                result,
                "no evaluation",
                r -> {
                    assertEquals(200, r.getResponse().getStatus(), "status");
                    assertEquals(List.of(), PDP.requests(), "evaluations");
                    pdp.verify(0, postRequestedFor(urlPathEqualTo(MockPdp.PATH)));
                });
    }
}
