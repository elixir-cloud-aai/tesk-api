package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.tes.exception.AuthorisationUnavailableException;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/** The hook of tes-authzen/1: startup checks, claim rule, owners and the requests it sends */
public class TesAuthzTest {

    private static final String ISS = "https://idp.example.org";
    private static final String VO = "urn:geant:lifescience-ri.eu:group:example-vo";
    private static final String SUBGROUP = VO + ":analysts#login.aai.lifescience-ri.eu";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path dir;

    private DecisionServiceClient client;
    private List<Map<String, Object>> bodies;
    private List<String> requestIds;

    @BeforeEach
    public void setUp() throws Exception {
        this.client = mock(DecisionServiceClient.class);
        this.bodies = new ArrayList<>();
        this.requestIds = new ArrayList<>();
    }

    /** Builder of settings, with all unset */
    private static class S {
        String issuer;
        String claimName;
        List<String> values = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        String url;
        String timeout;
        List<String> claims = new ArrayList<>();
        String credentialFile;

        AuthzSettings build() {
            return new AuthzSettings(
                    issuer, claimName, values, prefixes, url, timeout, claims, credentialFile);
        }
    }

    private static S pdpSettings() {
        S s = new S();
        s.issuer = ISS;
        s.url = "http://127.0.0.1:18192/access/v1/evaluation";
        s.claims = new ArrayList<>(Collections.singletonList("eduperson_entitlement"));
        return s;
    }

    private TesAuthz authz(S settings) {
        return new TesAuthz(settings.build(), true, () -> "tes-authz-test", null, this.client);
    }

    /** The client answers the decisions in order, recording the requests */
    @SuppressWarnings("unchecked")
    private void decisions(Boolean... answers) throws Exception {
        final int[] next = {0};
        when(this.client.evaluate(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            this.bodies.add((Map<String, Object>) invocation.getArgument(0));
                            this.requestIds.add(invocation.getArgument(1));
                            Boolean answer = answers[Math.min(next[0]++, answers.length - 1)];
                            if (answer == null) {
                                throw new DecisionServiceClient.Failure("down");
                            }
                            return answer;
                        });
    }

    private static User user(String sub, Map<String, Object> userInfo) {
        Map<String, Object> claims = new LinkedHashMap<>(userInfo);
        claims.put("sub", sub);
        return User.builder(sub).claims(claims).build();
    }

    private static User alice() {
        return user(
                "alice-sub",
                Collections.singletonMap("eduperson_entitlement", Arrays.asList(SUBGROUP)));
    }

    private static User serviceClient(Object scope) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "protes");
        claims.put("client_id", "protes");
        claims.put("iss", "https://clients.example.org");
        if (scope != null) {
            claims.put("scope", scope);
        }
        return User.builder("protes")
                .serviceClient(true)
                .claims(claims)
                .issuer("https://clients.example.org")
                .build();
    }

    /** The JSON as sent (numbers as written, e.g. a BigDecimal 1 as 1) */
    private static JsonNode json(Object value) {
        return JSON.readTree(JSON.writeValueAsString(value));
    }

    // startup checks

    @Test
    public void unset_noDecisionServiceAndNoRule() {
        TesAuthz authz = new TesAuthz(new S().build(), false, () -> "id", null);
        assertThat(authz.isDecisionService()).isFalse();
        assertThatCode(() -> authz.gate(User.builder("x").build())).doesNotThrowAnyException();
        assertThatCode(() -> authz.checkCreate(alice(), new TesTask())).doesNotThrowAnyException();
    }

    @Test
    public void startup_ruleWithoutAuth() {
        S s = new S();
        s.claimName = "groups";
        s.values.add("a");
        assertThatThrownBy(() -> new TesAuthz(s.build(), false, () -> "id", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth profile");
    }

    @Test
    public void startup_decisionServiceWithoutAuth() {
        assertThatThrownBy(() -> new TesAuthz(pdpSettings().build(), false, () -> "id", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth profile");
    }

    @Test
    public void startup_ruleWithoutValuesOrPrefixes() {
        S s = new S();
        s.claimName = "groups";
        assertThatThrownBy(() -> authz(s)).hasMessageContaining(".values or .prefixes");
    }

    @Test
    public void startup_valuesWithoutName() {
        S s = new S();
        s.prefixes.add("a:");
        assertThatThrownBy(() -> authz(s)).hasMessageContaining("need tesk.api.authz");
        S t = new S();
        t.values.add("a");
        assertThatThrownBy(() -> authz(t)).hasMessageContaining("need tesk.api.authz");
    }

    @Test
    public void startup_emptyValueOrPrefix() {
        S s = new S();
        s.claimName = "groups";
        s.values.add("");
        assertThatThrownBy(() -> authz(s)).hasMessageContaining("empty string");
        S t = new S();
        t.claimName = "groups";
        t.values.add("a");
        t.prefixes.add("");
        assertThatThrownBy(() -> authz(t)).hasMessageContaining("empty string");
    }

    @Test
    public void startup_ruleOnlyWithPrefixes() {
        S s = new S();
        s.claimName = "groups";
        s.prefixes.add("a:");
        assertThat(authz(s).isDecisionService()).isFalse();
    }

    @Test
    public void startup_decisionServiceKeysWithoutUrl() {
        S s = new S();
        s.timeout = "3";
        assertThatThrownBy(() -> authz(s)).hasMessageContaining("needs its url");
        S t = new S();
        t.claims.add("c");
        assertThatThrownBy(() -> authz(t)).hasMessageContaining("needs its url");
        S u = new S();
        u.credentialFile = "/f";
        assertThatThrownBy(() -> authz(u)).hasMessageContaining("needs its url");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://pdp/x", "pdp/x", "http:///x", "http://pd p/x", "https:x"})
    public void startup_urlNotHttpWithHost(String url) {
        S s = pdpSettings();
        s.url = url;
        assertThatThrownBy(() -> authz(s)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void startup_withoutIssuer() {
        S s = pdpSettings();
        s.issuer = null;
        assertThatThrownBy(() -> authz(s)).hasMessageContaining("tesk.api.authz.issuer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "x", "Infinity", "NaN"})
    public void startup_badTimeout(String timeout) {
        S s = pdpSettings();
        s.timeout = timeout;
        assertThatThrownBy(() -> authz(s)).hasMessageContaining("timeout");
    }

    @Test
    public void startup_credentialFile() throws Exception {
        S s = pdpSettings();
        s.credentialFile = dir.resolve("missing").toString();
        assertThatThrownBy(() -> authz(s)).hasMessageContaining("credential-file");
        Path empty = dir.resolve("empty");
        Files.write(empty, "\n".getBytes(StandardCharsets.UTF_8));
        s.credentialFile = empty.toString();
        assertThatThrownBy(() -> authz(s)).hasMessageContaining("credential-file");
        Path good = dir.resolve("good");
        Files.write(good, "secret".getBytes(StandardCharsets.UTF_8));
        s.credentialFile = good.toString();
        s.timeout = "0.0001";
        assertThat(authz(s).isDecisionService()).isTrue();
    }

    /** A real client is built without one given; plain http elsewhere than loopback only warns */
    @Test
    public void startup_realClientAndPlainHttp() throws Exception {
        S s = pdpSettings();
        s.url = "http://pdp.example.org/access/v1/evaluation";
        Path good = dir.resolve("good");
        Files.write(good, "secret".getBytes(StandardCharsets.UTF_8));
        s.credentialFile = good.toString();
        assertThat(new TesAuthz(s.build(), true, () -> "id", null).isDecisionService()).isTrue();
        s.url = "https://pdp.example.org/access/v1/evaluation";
        s.credentialFile = null;
        assertThat(new TesAuthz(s.build(), true, () -> "id", null).isDecisionService()).isTrue();
    }

    @Test
    public void isLoopback() {
        assertThat(TesAuthz.isLoopback("localhost")).isTrue();
        assertThat(TesAuthz.isLoopback("127.0.0.1")).isTrue();
        assertThat(TesAuthz.isLoopback("[::1]")).isTrue();
        assertThat(TesAuthz.isLoopback("127.evil.org")).isFalse();
        assertThat(TesAuthz.isLoopback("pdp")).isFalse();
    }

    // claim rule

    private TesAuthz rule(String name, List<String> values, List<String> prefixes) {
        S s = new S();
        s.claimName = name;
        s.values.addAll(values);
        s.prefixes.addAll(prefixes);
        return authz(s);
    }

    @Test
    public void gate_exactValue() {
        TesAuthz authz = rule("groups", Arrays.asList("a"), Collections.emptyList());
        assertThatCode(() -> authz.gate(user("u", Collections.singletonMap("groups", "a"))))
                .doesNotThrowAnyException();
    }

    @Test
    public void gate_prefix() {
        TesAuthz authz =
                rule("eduperson_entitlement", Collections.emptyList(), Arrays.asList(VO + ":"));
        assertThatCode(() -> authz.gate(alice())).doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                authz.gate(
                                        user(
                                                "u",
                                                Collections.singletonMap(
                                                        "eduperson_entitlement",
                                                        Arrays.asList(VO + "x#y")))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    public void gate_dottedPathAndUriName() {
        TesAuthz path = rule("ctx.groups", Arrays.asList("a"), Collections.emptyList());
        assertThatCode(
                        () ->
                                path.gate(
                                        user(
                                                "u",
                                                Collections.singletonMap(
                                                        "ctx",
                                                        Collections.singletonMap(
                                                                "groups", Arrays.asList("a"))))))
                .doesNotThrowAnyException();
        TesAuthz uri = rule("https://e.org/groups", Arrays.asList("a"), Collections.emptyList());
        assertThatCode(
                        () ->
                                uri.gate(
                                        user(
                                                "u",
                                                Collections.singletonMap(
                                                        "https://e.org/groups", "a"))))
                .doesNotThrowAnyException();
    }

    @Test
    public void gate_missingWrongTypeOrNoMatch() {
        TesAuthz authz = rule("groups", Arrays.asList("a"), Collections.emptyList());
        for (Object value :
                Arrays.asList(
                        null,
                        1,
                        Collections.singletonMap("a", "a"),
                        Arrays.asList("a", 1),
                        Arrays.asList("b"),
                        Collections.emptyList())) {
            Map<String, Object> claims = new HashMap<>();
            if (value != null) {
                claims.put("groups", value);
            }
            assertThatThrownBy(() -> authz.gate(user("u", claims)))
                    .as("claim %s", value)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    /** Service clients without the claim don't pass the rule */
    @Test
    public void gate_serviceClientWithoutClaim() {
        TesAuthz authz = rule("groups", Arrays.asList("a"), Collections.emptyList());
        assertThatThrownBy(() -> authz.gate(serviceClient("openid")))
                .isInstanceOf(AccessDeniedException.class);
    }

    // owners

    @Test
    public void ownerId() {
        TesAuthz authz = authz(pdpSettings());
        assertThat(authz.ownerId(alice())).contains(ISS + "|alice-sub");
        assertThat(authz.ownerId(serviceClient(null)))
                .contains("https://clients.example.org|protes");
        assertThat(authz.ownerId(User.builder("x").build())).contains(ISS + "|x");
        assertThat(authz(new S()).ownerId(alice())).isEmpty();
    }

    /** A service client's tasks are the client's, also if its token names another sub */
    @Test
    public void ownerId_serviceClientWithOtherSub() {
        Map<String, Object> claims = new HashMap<>(serviceClient(null).getClaims());
        claims.put("sub", "some-user");
        User client =
                User.builder("protes")
                        .serviceClient(true)
                        .claims(claims)
                        .issuer("https://clients.example.org")
                        .build();
        assertThat(authz(pdpSettings()).ownerId(client))
                .contains("https://clients.example.org|protes");
    }

    @Test
    public void ownerOfCreator() {
        assertThat(authz(pdpSettings()).ownerOfCreator("bob")).contains(ISS + "|bob");
        assertThat(authz(pdpSettings()).ownerOfCreator(null)).isEmpty();
        assertThat(authz(new S()).ownerOfCreator("bob")).isEmpty();
        assertThat(
                        new TesAuthz(pdpSettings().build(), true, () -> "id", ISS, this.client)
                                .ownerOfCreator("bob"))
                .contains(ISS + "|bob");
        assertThat(
                        new TesAuthz(
                                        pdpSettings().build(),
                                        true,
                                        () -> "id",
                                        "https://other",
                                        this.client)
                                .ownerOfCreator("bob"))
                .isEmpty();
    }

    // requests

    /** The golden request of case 7 (create), with TESK's subject: no client_id, no scope */
    @Test
    public void checkCreate_matchesGolden() throws Exception {
        decisions(true);
        JsonNode golden = JSON.readTree(getClass().getResourceAsStream("/authz/hook-create.json"));
        TesTask task = JSON.treeToValue(golden.get("submit"), TesTask.class);
        authz(pdpSettings()).checkCreate(alice(), task);

        JsonNode expected = golden.get("request").deepCopy();
        tools.jackson.databind.node.ObjectNode subject =
                (tools.jackson.databind.node.ObjectNode) expected.get("subject");
        subject.put("id", ISS + "|alice-sub");
        tools.jackson.databind.node.ObjectNode properties =
                (tools.jackson.databind.node.ObjectNode) subject.get("properties");
        properties.put("iss", ISS);
        properties.put("sub", "alice-sub");
        properties.remove("client_id");
        properties.remove("scope");
        assertThat(json(this.bodies.get(0))).isEqualTo(expected);
    }

    @Test
    public void checkCreate_denied() throws Exception {
        decisions(false);
        TesTask task = new TesTask();
        assertThatThrownBy(() -> authz(pdpSettings()).checkCreate(alice(), task))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(json(this.bodies.get(0)).at("/resource/properties/task"))
                .isEqualTo(JSON.readTree("{\"executors\": []}"));
    }

    @Test
    public void ask_failureIs503() throws Exception {
        decisions((Boolean) null);
        assertThatThrownBy(() -> authz(pdpSettings()).checkCreate(alice(), new TesTask()))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }

    @Test
    public void mayRead_existingTask() throws Exception {
        decisions(true);
        Map<String, String> tags = Collections.singletonMap("project", "demo");
        boolean allowed =
                authz(pdpSettings())
                        .mayRead(
                                alice(),
                                "task-1",
                                new TesAuthz.TaskMeta("o", "RUNNING", tags),
                                TaskView.BASIC);
        assertThat(allowed).isTrue();
        assertThat(json(this.bodies.get(0)))
                .isEqualTo(
                        JSON.readTree(
                                "{\"subject\": {\"type\": \"user\", \"id\": \""
                                        + ISS
                                        + "|alice-sub\", \"properties\": {\"iss\": \""
                                        + ISS
                                        + "\", \"sub\": \"alice-sub\", \"claims\":"
                                        + " {\"eduperson_entitlement\": [\""
                                        + SUBGROUP
                                        + "\"]}}},"
                                        + " \"action\": {\"name\": \"GetTask\", \"properties\":"
                                        + " {\"view\": \"BASIC\"}},"
                                        + " \"resource\": {\"type\": \"tes_task\", \"id\":"
                                        + " \"task-1\", \"properties\": {\"owner\": \"o\","
                                        + " \"state\": \"RUNNING\", \"tags\": {\"project\":"
                                        + " \"demo\"}}},"
                                        + " \"context\": {\"tes\": {\"profile\":"
                                        + " \"tes-authzen/1\", \"service_id\":"
                                        + " \"tes-authz-test\"}}}"));
    }

    /** Missing tasks: properties {}; legacy tasks: no owner, tags {} */
    @Test
    public void mayRead_missingAndLegacyTask() throws Exception {
        decisions(false);
        TesAuthz authz = authz(pdpSettings());
        assertThat(authz.mayRead(alice(), "gone", null, TaskView.MINIMAL)).isFalse();
        authz.mayRead(alice(), "old", new TesAuthz.TaskMeta(null, "COMPLETE", null), null);
        assertThat(json(this.bodies.get(0)).get("resource"))
                .isEqualTo(
                        JSON.readTree(
                                "{\"type\": \"tes_task\", \"id\": \"gone\", \"properties\": {}}"));
        assertThat(json(this.bodies.get(1)).at("/resource/properties"))
                .isEqualTo(JSON.readTree("{\"state\": \"COMPLETE\", \"tags\": {}}"));
        assertThat(json(this.bodies.get(1)).at("/action/properties"))
                .isEqualTo(JSON.readTree("{}"));
        assertThat(this.requestIds.get(0)).isNotEqualTo(this.requestIds.get(1));
    }

    /** Service clients: client_id and scope; claims not in the allowlist aren't sent */
    @Test
    public void subject_serviceClient() throws Exception {
        TesAuthz authz = authz(pdpSettings());
        assertThat(json(authz.subject(serviceClient("openid  tes"))))
                .isEqualTo(
                        JSON.readTree(
                                "{\"type\": \"user\", \"id\":"
                                        + " \"https://clients.example.org|protes\","
                                        + " \"properties\": {\"iss\":"
                                        + " \"https://clients.example.org\", \"sub\": \"protes\","
                                        + " \"client_id\": \"protes\", \"scope\": [\"openid\","
                                        + " \"tes\"], \"claims\": {}}}"));
        assertThat(json(authz.subject(serviceClient(Arrays.asList("a")))).at("/properties/scope"))
                .isEqualTo(JSON.readTree("[\"a\"]"));
        assertThat(json(authz.subject(serviceClient(null))).at("/properties").has("scope"))
                .isFalse();
        assertThat(json(authz.subject(serviceClient(1))).at("/properties").has("scope")).isFalse();
    }

    @Test
    public void checkCancel_allowed() throws Exception {
        decisions(true);
        authz(pdpSettings()).checkCancel(alice(), "t", new TesAuthz.TaskMeta("o", "RUNNING", null));
        assertThat(this.bodies).hasSize(1);
        assertThat(json(this.bodies.get(0)).at("/action"))
                .isEqualTo(JSON.readTree("{\"name\": \"CancelTask\", \"properties\": {}}"));
    }

    @Test
    public void checkCancel_readableButNotCancellable() throws Exception {
        decisions(false, true);
        assertThatThrownBy(
                        () ->
                                authz(pdpSettings())
                                        .checkCancel(
                                                alice(),
                                                "t",
                                                new TesAuthz.TaskMeta("o", "RUNNING", null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(json(this.bodies.get(1)).at("/action"))
                .isEqualTo(JSON.readTree("{\"name\": \"GetTask\", \"properties\": {}}"));
    }

    @Test
    public void checkCancel_notReadable() throws Exception {
        decisions(false, false);
        assertThatThrownBy(
                        () ->
                                authz(pdpSettings())
                                        .checkCancel(
                                                alice(),
                                                "t",
                                                new TesAuthz.TaskMeta("o", "RUNNING", null)))
                .isInstanceOf(TaskNotFoundException.class);
    }

    /** Case 11c: a missing task costs the same two calls as a task of others, then 404 */
    @Test
    public void checkCancel_missingTask() throws Exception {
        decisions(true, true);
        assertThatThrownBy(() -> authz(pdpSettings()).checkCancel(alice(), "gone", null))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(this.bodies).hasSize(2);
    }

    @Test
    public void listsAll() throws Exception {
        decisions(true, false);
        assertThat(authz(pdpSettings()).listsAll(alice(), TaskView.MINIMAL)).isTrue();
        assertThat(json(this.bodies.get(0)).get("resource"))
                .isEqualTo(JSON.readTree("{\"type\": \"tes_task_set\", \"id\": \"all\"}"));
        assertThat(json(this.bodies.get(1)).get("resource"))
                .isEqualTo(JSON.readTree("{\"type\": \"tes_task_set\", \"id\": \"own\"}"));
        assertThat(json(this.bodies.get(1)).at("/action/properties/view").asString())
                .isEqualTo("MINIMAL");
    }

    @Test
    public void listsOwn() throws Exception {
        decisions(false, true);
        assertThat(authz(pdpSettings()).listsAll(alice(), TaskView.FULL)).isFalse();
    }

    @Test
    public void listsNothing() throws Exception {
        decisions(false, false);
        assertThatThrownBy(() -> authz(pdpSettings()).listsAll(alice(), TaskView.FULL))
                .isInstanceOf(AccessDeniedException.class);
        verify(this.client, times(2)).evaluate(any(), anyString());
    }

    @Test
    public void subject_withoutIssuer() throws Exception {
        TesAuthz authz = authz(new S());
        assertThatThrownBy(() -> authz.subject(alice())).isInstanceOf(IllegalStateException.class);
        verify(this.client, never()).evaluate(any(), anyString());
    }
}
