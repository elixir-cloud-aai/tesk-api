package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenPrincipal;
import uk.ac.ebi.tsc.tesk.tes.exception.AuthorisationUnavailableException;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.CancelDecision;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.ListScope;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.Operation;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.TaskFacts;

/**
 * The decisions of get, list and cancel when they are switched on: the requests (golden values of
 * tes-authz), every answer, and failing closed and open
 */
public class OperationAuthorisationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String ISS = "https://login.aai.lifescience-ri.eu/oidc/";
    private static final String CLAIM = "eduperson_entitlement";
    private static final List<String> ENTITLEMENTS =
            List.of(
                    "urn:geant:lifescience-ri.eu:group:example-vo:analysts#login.aai.lifescience-ri.eu",
                    "urn:geant:lifescience-ri.eu:group:other-vo:sub#login.aai.lifescience-ri.eu");

    private static final Set<Operation> ALL = EnumSet.allOf(Operation.class);
    private static final Set<Operation> NONE = EnumSet.noneOf(Operation.class);

    private DecisionServiceClient decisionService;
    private TokenPrincipal alice;
    private User user;
    private TaskFacts own;
    private TaskFacts foreign;
    private int requestIds;

    @BeforeEach
    public void mocks() {
        this.decisionService = mock(DecisionServiceClient.class);
        this.alice =
                new TokenPrincipal(
                        ISS,
                        "alice-sub",
                        TokenPrincipal.Kind.USER,
                        "tes-client",
                        Map.of("scope", "openid " + CLAIM, CLAIM, ENTITLEMENTS));
        this.user = User.builder(this.alice.owner()).principal(this.alice).build();
        this.own = new TaskFacts(this.alice.owner(), TesState.RUNNING, Map.of("project", "demo"));
        this.foreign = new TaskFacts(ISS + "|bob", TesState.RUNNING, null);
        this.requestIds = 0;
    }

    private OperationAuthorisation authz(Set<Operation> operations, Set<Operation> failOpen) {
        return new OperationAuthorisation(
                this.decisionService,
                new EvaluationRequests(List.of(CLAIM), () -> "tes-authz-test"),
                operations,
                failOpen,
                body -> "request-" + ++this.requestIds);
    }

    private OperationAuthorisation authz() {
        return authz(ALL, NONE);
    }

    private static JsonNode json(String answer) {
        return JSON.readTree(answer);
    }

    private void answer(String... answers) throws Exception {
        JsonNode first = json(answers[0]);
        JsonNode[] rest = new JsonNode[answers.length - 1];
        for (int i = 1; i < answers.length; i++) {
            rest[i - 1] = json(answers[i]);
        }
        when(this.decisionService.answer(any(), anyString())).thenReturn(first, rest);
    }

    private void fail() throws Exception {
        when(this.decisionService.answer(any(), anyString()))
                .thenThrow(new DecisionServiceClient.Failure("status 500"));
    }

    /** The evaluation requests sent, in order */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sent(int count) throws Exception {
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(this.decisionService, times(count)).answer(body.capture(), anyString());
        return body.getAllValues();
    }

    private List<String> sentRequestIds(int count) throws Exception {
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(this.decisionService, times(count)).answer(any(), ids.capture());
        return ids.getAllValues();
    }

    /**
     * The request of a golden case of tes-authz ({@code golden/requests}), with TESK's owner ID
     * ({@code <iss>|<sub>}) for the golden single-issuer owner
     */
    private Object golden(String name) throws Exception {
        JsonNode golden;
        try (InputStream in = getClass().getResourceAsStream("/authz/requests/" + name + ".json")) {
            golden = JSON.readTree(in);
        }
        ObjectNode expected = (ObjectNode) golden.get("request").deepCopy();
        ((ObjectNode) expected.get("subject")).put("id", this.alice.owner());
        JsonNode properties = expected.get("resource").get("properties");
        if (properties != null && properties.has("owner")) {
            ((ObjectNode) properties).put("owner", this.alice.owner());
        }
        return JSON.convertValue(expected, Object.class);
    }

    private static Object plain(Map<String, Object> request) {
        return JSON.convertValue(JSON.valueToTree(request), Object.class);
    }

    @Test
    public void decides_theSwitchedOnOperations() {
        OperationAuthorisation authz = authz(EnumSet.of(Operation.CREATE, Operation.LIST), NONE);
        assertThat(authz.decides(Operation.LIST)).isTrue();
        assertThat(authz.decides(Operation.GET)).isFalse();
        assertThat(authz(NONE, NONE).decides(Operation.CREATE)).isFalse();
    }

    @Test
    public void get_ownTask_matchesTheGoldenCase() throws Exception {
        answer("{\"decision\": true}");

        assertThat(authz().mayGet(this.user, "task-a1", this.own, TaskView.MINIMAL)).isTrue();

        assertThat(plain(sent(1).get(0))).isEqualTo(golden("get-own-profile-3"));
    }

    @Test
    public void get_missingTask_matchesTheGoldenCase() throws Exception {
        answer("{\"decision\": false}");

        assertThat(authz().mayGet(this.user, "no-such-task", null, TaskView.MINIMAL)).isFalse();

        assertThat(plain(sent(1).get(0))).isEqualTo(golden("get-missing-profile-3"));
    }

    @Test
    public void get_foreignTask_widened() throws Exception {
        answer("{\"decision\": true}");

        assertThat(authz().mayGet(this.user, "task-b1", this.foreign, TaskView.FULL)).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) sent(1).get(0).get("resource");
        assertThat(resource)
                .isEqualTo(
                        Map.of(
                                "type",
                                "tes_task",
                                "id",
                                "task-b1",
                                "properties",
                                Map.of(
                                        "owner",
                                        ISS + "|bob",
                                        "state",
                                        "RUNNING",
                                        "tags",
                                        Map.of())));
    }

    @Test
    public void get_failsClosed() throws Exception {
        fail();

        assertThatThrownBy(() -> authz().mayGet(this.user, "task-a1", this.own, TaskView.MINIMAL))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }

    @Test
    public void get_failsOpen_toTheOwnerRule() throws Exception {
        fail();
        OperationAuthorisation authz = authz(ALL, EnumSet.of(Operation.GET));

        assertThat(authz.mayGet(this.user, "task-a1", this.own, TaskView.MINIMAL)).isTrue();
        assertThat(authz.mayGet(this.user, "task-b1", this.foreign, TaskView.MINIMAL)).isFalse();
        assertThat(authz.mayGet(this.user, "no-such-task", null, TaskView.MINIMAL)).isFalse();
    }

    @Test
    public void cancel_ownTask_matchesTheGoldenCase() throws Exception {
        answer("{\"decision\": true}");

        assertThat(authz().mayCancel(this.user, "task-a1", this.own))
                .isEqualTo(CancelDecision.ALLOWED);

        assertThat(plain(sent(1).get(0))).isEqualTo(golden("cancel-own-profile-3"));
    }

    /** A missing task, denied: get isn't switched on, so the owner rule decides the 404 */
    @Test
    public void cancel_missingTask_matchesTheGoldenCase() throws Exception {
        answer("{\"decision\": false}");
        OperationAuthorisation authz = authz(EnumSet.of(Operation.CREATE, Operation.CANCEL), NONE);

        assertThat(authz.mayCancel(this.user, "no-such-task", null))
                .isEqualTo(CancelDecision.NOT_FOUND);

        assertThat(plain(sent(1).get(0))).isEqualTo(golden("cancel-missing-profile-3"));
    }

    /** Denied, but get is allowed: 403, after a GetTask without a view, with a new request ID */
    @Test
    public void cancel_deniedButReadable_isForbidden() throws Exception {
        answer("{\"decision\": false}", "{\"decision\": true}");

        assertThat(authz().mayCancel(this.user, "task-a1", this.own))
                .isEqualTo(CancelDecision.FORBIDDEN);

        List<Map<String, Object>> requests = sent(2);
        assertThat(requests.get(1).get("action"))
                .isEqualTo(Map.of("name", "GetTask", "properties", Map.of()));
        assertThat(requests.get(1).get("resource")).isEqualTo(requests.get(0).get("resource"));
        assertThat(sentRequestIds(2)).containsExactly("request-1", "request-2");
    }

    @Test
    public void cancel_deniedAndNotReadable_isNotFound() throws Exception {
        answer("{\"decision\": false}", "{\"decision\": false}");

        assertThat(authz().mayCancel(this.user, "task-a1", this.own))
                .isEqualTo(CancelDecision.NOT_FOUND);
    }

    /** A missing task is 404 even if get would be allowed */
    @Test
    public void cancel_deniedForAMissingTask_isNotFound() throws Exception {
        answer("{\"decision\": false}", "{\"decision\": true}");

        assertThat(authz().mayCancel(this.user, "no-such-task", null))
                .isEqualTo(CancelDecision.NOT_FOUND);
    }

    /** Get not switched on: the owner rule decides whether a deny is 403 or 404, without a call */
    @Test
    public void cancel_denied_getByTheOwnerRule() throws Exception {
        answer("{\"decision\": false}");
        OperationAuthorisation authz = authz(EnumSet.of(Operation.CREATE, Operation.CANCEL), NONE);

        assertThat(authz.mayCancel(this.user, "task-a1", this.own))
                .isEqualTo(CancelDecision.FORBIDDEN);
        assertThat(authz.mayCancel(this.user, "task-b1", this.foreign))
                .isEqualTo(CancelDecision.NOT_FOUND);
        sent(2);
    }

    /** The extra GetTask fails: get's failure mode decides */
    @Test
    public void cancel_denied_getFails() throws Exception {
        when(this.decisionService.answer(any(), anyString()))
                .thenReturn(json("{\"decision\": false}"))
                .thenThrow(new DecisionServiceClient.Failure("timeout"));

        assertThatThrownBy(() -> authz().mayCancel(this.user, "task-a1", this.own))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }

    @Test
    public void cancel_denied_getFailsOpen() throws Exception {
        when(this.decisionService.answer(any(), anyString()))
                .thenReturn(json("{\"decision\": false}"))
                .thenThrow(new DecisionServiceClient.Failure("timeout"));

        assertThat(authz(ALL, EnumSet.of(Operation.GET)).mayCancel(this.user, "task-a1", this.own))
                .isEqualTo(CancelDecision.FORBIDDEN);
    }

    @Test
    public void cancel_failsClosed() throws Exception {
        fail();

        assertThatThrownBy(() -> authz().mayCancel(this.user, "task-a1", this.own))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }

    /** Failing open: the owner rule, also for the 404, without a GetTask */
    @Test
    public void cancel_failsOpen_toTheOwnerRule() throws Exception {
        fail();
        OperationAuthorisation authz = authz(ALL, EnumSet.of(Operation.CANCEL));

        assertThat(authz.mayCancel(this.user, "task-a1", this.own))
                .isEqualTo(CancelDecision.ALLOWED);
        assertThat(authz.mayCancel(this.user, "task-b1", this.foreign))
                .isEqualTo(CancelDecision.NOT_FOUND);
        sent(2);
    }

    @Test
    public void list_matchesTheGoldenCase() throws Exception {
        answer("{\"decision\": true}");

        assertThat(authz().listScope(this.user, TaskView.MINIMAL)).isEqualTo(ListScope.OWN);

        assertThat(plain(sent(1).get(0))).isEqualTo(golden("list-own-profile-3"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"decision\": true}",
                "{\"decision\": true, \"context\": {}}",
                "{\"decision\": true, \"context\": {\"tes\": {}}}",
                "{\"decision\": true, \"context\": {\"tes\": {\"list_scope\": \"own\"}}}"
            })
    public void listScope_own(String answer) {
        assertThat(OperationAuthorisation.listScope(json(answer))).contains(ListScope.OWN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"decision\": false}",
                "{\"decision\": false, \"context\": {\"tes\": {\"list_scope\": \"all\"}}}",
                "{\"decision\": \"true\", \"context\": {\"tes\": {\"list_scope\": \"all\"}}}",
                "{\"decision\": true, \"context\": {\"tes\": {\"list_scope\": \"none\"}}}"
            })
    public void listScope_none(String answer) {
        assertThat(OperationAuthorisation.listScope(json(answer))).contains(ListScope.NONE);
    }

    @Test
    public void listScope_all() {
        assertThat(
                        OperationAuthorisation.listScope(
                                json(
                                        "{\"decision\": true, \"context\": {\"tes\":"
                                                + " {\"list_scope\": \"all\"}}}")))
                .contains(ListScope.ALL);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"decision\": true, \"context\": null}",
                "{\"decision\": true, \"context\": []}",
                "{\"decision\": true, \"context\": {\"tes\": \"own\"}}",
                "{\"decision\": true, \"context\": {\"tes\": {\"list_scope\": null}}}",
                "{\"decision\": true, \"context\": {\"tes\": {\"list_scope\": 1}}}",
                "{\"decision\": true, \"context\": {\"tes\": {\"list_scope\": \"ALL\"}}}",
                "{\"decision\": true, \"context\": {\"tes\": {\"list_scope\": \"group\"}}}"
            })
    public void listScope_malformed(String answer) {
        assertThat(OperationAuthorisation.listScope(json(answer))).isEmpty();
    }

    @Test
    public void list_malformedScope_failsClosed() throws Exception {
        answer("{\"decision\": true, \"context\": {\"tes\": {\"list_scope\": \"group\"}}}");

        assertThatThrownBy(() -> authz().listScope(this.user, TaskView.BASIC))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }

    @Test
    public void list_malformedScope_failsOpen() throws Exception {
        answer("{\"decision\": true, \"context\": {\"tes\": {\"list_scope\": \"group\"}}}");

        assertThat(authz(ALL, EnumSet.of(Operation.LIST)).listScope(this.user, TaskView.BASIC))
                .isEqualTo(ListScope.OWN);
    }

    @Test
    public void list_scopes() throws Exception {
        answer(
                "{\"decision\": true, \"context\": {\"tes\": {\"list_scope\": \"all\"}}}",
                "{\"decision\": false}");

        assertThat(authz().listScope(this.user, TaskView.FULL)).isEqualTo(ListScope.ALL);
        assertThat(authz().listScope(this.user, TaskView.FULL)).isEqualTo(ListScope.NONE);
    }

    @Test
    public void list_failsClosed() throws Exception {
        fail();

        assertThatThrownBy(() -> authz().listScope(this.user, TaskView.MINIMAL))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }

    @Test
    public void list_failsOpen_toTheOwnerRule() throws Exception {
        fail();

        assertThat(authz(ALL, EnumSet.of(Operation.LIST)).listScope(this.user, TaskView.MINIMAL))
                .isEqualTo(ListScope.OWN);
    }

    /** Claims from the token only: no user info, and the token itself is never sent */
    @Test
    public void request_claimsFromTheTokenOnly() throws Exception {
        answer("{\"decision\": true}");
        TokenPrincipal bob =
                new TokenPrincipal(ISS, "bob", TokenPrincipal.Kind.USER, null, Map.of());
        User bobUser = User.builder(bob.owner()).principal(bob).build();

        authz().mayGet(bobUser, "task-b1", this.foreign, TaskView.MINIMAL);

        @SuppressWarnings("unchecked")
        Map<String, Object> subject = (Map<String, Object>) sent(1).get(0).get("subject");
        assertThat(subject.get("properties"))
                .isEqualTo(Map.of("iss", ISS, "sub", "bob", "claims", Map.of()));
    }

    /** Not expected with authentication: a caller without a validated token is denied, unasked */
    @Test
    public void noPrincipal_isDenied() throws Exception {
        User anonymous = User.builder("someone").build();
        OperationAuthorisation authz = authz();

        assertThat(authz.mayGet(anonymous, "task-a1", this.own, TaskView.MINIMAL)).isFalse();
        assertThat(authz.mayCancel(anonymous, "task-a1", this.own))
                .isEqualTo(CancelDecision.NOT_FOUND);
        assertThat(authz.listScope(anonymous, TaskView.MINIMAL)).isEqualTo(ListScope.NONE);
        verify(this.decisionService, never()).answer(any(), anyString());
    }

    /** The task's state may be unknown */
    @Test
    public void task_withoutState() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("owner", "o");
        properties.put("state", null);
        properties.put("tags", Map.of());
        assertThat(
                        OperationAuthorisation.task("t", new TaskFacts("o", null, null))
                                .get("properties"))
                .isEqualTo(properties);
    }

    /** The constructor for production: random request IDs, new for each evaluation */
    @Test
    public void requestIds_random() throws Exception {
        answer("{\"decision\": true}");
        OperationAuthorisation authz =
                new OperationAuthorisation(
                        this.decisionService,
                        new EvaluationRequests(List.of(), () -> null),
                        ALL,
                        NONE);

        authz.mayGet(this.user, "a", this.own, TaskView.MINIMAL);
        authz.mayGet(this.user, "a", this.own, TaskView.MINIMAL);

        List<String> ids = new ArrayList<>(sentRequestIds(2));
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1)).hasSize(36);
    }
}
