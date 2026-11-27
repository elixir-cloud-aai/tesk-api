package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.config.security.token.InvalidTokenException;
import uk.ac.ebi.tsc.tesk.config.security.token.IssuerUnavailableException;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenPrincipal;
import uk.ac.ebi.tsc.tesk.config.security.token.UserInfoClient;
import uk.ac.ebi.tsc.tesk.tes.exception.AuthorisationUnavailableException;
import uk.ac.ebi.tsc.tesk.tes.exception.UserInfoRefusedException;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * The decision on create: the request to the decision service (golden values of tes-authz), the
 * claims from the token or the user info, and every outcome
 */
public class CreateAuthorisationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String ISS = "https://login.example.org/oidc/";
    private static final String ENTITLEMENT =
            "urn:geant:lifescience-ri.eu:group:example-vo:analysts#login.aai.lifescience-ri.eu";
    private static final String CLAIM = "eduperson_entitlement";

    private DecisionServiceClient decisionService;
    private UserInfoClient userInfo;
    private String accessToken;
    private String serviceId;

    @BeforeEach
    public void mocks() throws Exception {
        this.decisionService = mock(DecisionServiceClient.class);
        this.userInfo = mock(UserInfoClient.class);
        this.accessToken = "token-1";
        this.serviceId = "tes-authz-test";
        decide(true);
    }

    @AfterEach
    public void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void decide(boolean decision) throws Exception {
        when(this.decisionService.evaluate(any(), anyString())).thenReturn(decision);
    }

    private CreateAuthorisation authz(List<String> claims) {
        return new CreateAuthorisation(
                this.decisionService,
                this.userInfo,
                claims,
                () -> this.serviceId,
                () -> this.accessToken);
    }

    private static TokenPrincipal principal(
            String sub, TokenPrincipal.Kind kind, String clientId, Map<String, Object> claims) {
        return new TokenPrincipal(ISS, sub, kind, clientId, claims);
    }

    private static TokenPrincipal alice(Map<String, Object> claims) {
        return principal("alice", TokenPrincipal.Kind.USER, "portal", claims);
    }

    private static User user(TokenPrincipal principal) {
        return User.builder(principal.owner()).principal(principal).build();
    }

    /** The evaluation request sent */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sent() throws Exception {
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(this.decisionService).evaluate(body.capture(), anyString());
        return body.getValue();
    }

    /** The JSON value as plain objects, with all numbers as BigDecimals without trailing zeros */
    private static Object normalised(JsonNode node) {
        return normalisedValue(JSON.convertValue(node, Object.class));
    }

    private static Object normalisedValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(k, normalisedValue(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(v -> result.add(normalisedValue(v)));
            return result;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        return value;
    }

    /**
     * The request of tes-authz's golden case hook-create ({@code golden/hook/create.json}): only
     * the fixed projection of the task, stripped URLs, the caller as subject and owner, and the
     * allowlisted claim from the user info
     */
    @Test
    public void request_matchesTheGoldenCase() throws Exception {
        JsonNode golden;
        try (InputStream in = getClass().getResourceAsStream("/authz/hook-create.json")) {
            golden = JSON.readTree(in);
        }
        TesTask task = JSON.treeToValue(golden.get("submit"), TesTask.class);
        TokenPrincipal principal =
                alice(Map.of("scope", "openid eduperson_entitlement", "client_id", "portal"));
        when(this.userInfo.claims(principal, "token-1"))
                .thenReturn(Map.of("sub", "alice", CLAIM, List.of(ENTITLEMENT)));

        assertThat(authz(List.of(CLAIM)).mayCreate(task, user(principal))).isTrue();

        ObjectNode expected = (ObjectNode) golden.get("request").deepCopy();
        String owner = ISS + "|alice";
        ObjectNode subject = (ObjectNode) expected.get("subject");
        subject.put("id", owner);
        ObjectNode properties = (ObjectNode) subject.get("properties");
        properties.put("iss", ISS);
        properties.put("sub", "alice");
        properties.put("client_id", "portal");
        properties.putArray("scope").add("openid").add(CLAIM);
        ((ObjectNode) expected.get("resource").get("properties")).put("owner", owner);
        assertThat(normalisedValue(sent())).isEqualTo(normalised(expected));
    }

    @Test
    public void denied() throws Exception {
        decide(false);

        assertThat(authz(List.of()).mayCreate(new TesTask(), user(alice(Map.of())))).isFalse();
        verify(this.decisionService).evaluate(any(), anyString());
    }

    /** Any failure of the decision service: 503, never a decision */
    @Test
    public void decisionServiceFailure_isUnavailable() throws Exception {
        when(this.decisionService.evaluate(any(), anyString()))
                .thenThrow(new DecisionServiceClient.Failure("timeout"));

        assertThatThrownBy(() -> authz(List.of()).mayCreate(new TesTask(), user(alice(Map.of()))))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }

    /** A new request ID for each evaluation */
    @Test
    public void requestId_newForEachEvaluation() throws Exception {
        CreateAuthorisation authz = authz(List.of());
        authz.mayCreate(new TesTask(), user(alice(Map.of())));
        authz.mayCreate(new TesTask(), user(alice(Map.of())));
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(this.decisionService, org.mockito.Mockito.times(2)).evaluate(any(), ids.capture());
        assertThat(ids.getAllValues().get(0)).isNotEqualTo(ids.getAllValues().get(1));
    }

    /** A caller without a validated token (not expected with authentication) may not create */
    @Test
    public void noPrincipal_isDenied() throws Exception {
        assertThat(authz(List.of()).mayCreate(new TesTask(), User.builder("x").build())).isFalse();
        verify(this.decisionService, never()).evaluate(any(), anyString());
    }

    /** Claims in the token by their exact names: no user info */
    @Test
    public void claimsInTheToken_noUserInfo() throws Exception {
        TokenPrincipal principal = alice(Map.of(CLAIM, List.of("t")));

        authz(List.of(CLAIM)).mayCreate(new TesTask(), user(principal));

        verify(this.userInfo, never()).claims(any(), any());
        assertThat(claimsSent()).isEqualTo(Map.of(CLAIM, List.of("t")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> claimsSent() throws Exception {
        Map<String, Object> subject = (Map<String, Object>) sent().get("subject");
        return (Map<String, Object>)
                ((Map<String, Object>) subject.get("properties")).get("claims");
    }

    /** No claims listed (the default): no user info, empty claims */
    @Test
    public void noClaimsListed_noUserInfo() throws Exception {
        authz(List.of()).mayCreate(new TesTask(), user(alice(Map.of())));

        verify(this.userInfo, never()).claims(any(), any());
        assertThat(claimsSent()).isEmpty();
    }

    /** A claim found nowhere is left out */
    @Test
    public void claimFoundNowhere_isLeftOut() throws Exception {
        TokenPrincipal principal = alice(Map.of());
        when(this.userInfo.claims(principal, "token-1")).thenReturn(Map.of("sub", "alice"));

        authz(List.of(CLAIM)).mayCreate(new TesTask(), user(principal));

        assertThat(claimsSent()).isEmpty();
    }

    /** Service clients' tokens are never looked up (LS Login answers 403 for them) */
    @Test
    public void serviceClient_noUserInfo() throws Exception {
        TokenPrincipal client =
                principal("gateway", TokenPrincipal.Kind.SERVICE_CLIENT, "gateway", Map.of());

        authz(List.of(CLAIM)).mayCreate(new TesTask(), user(client));

        verify(this.userInfo, never()).claims(any(), any());
    }

    /** Neither are other clients' own tokens (sub = client), listed or not */
    @Test
    public void clientToken_noUserInfo() throws Exception {
        TokenPrincipal client =
                principal("unlisted", TokenPrincipal.Kind.USER, "unlisted", Map.of());

        authz(List.of(CLAIM)).mayCreate(new TesTask(), user(client));

        verify(this.userInfo, never()).claims(any(), any());
        assertThat(CreateAuthorisation.isClientToken(client)).isTrue();
        assertThat(CreateAuthorisation.isClientToken(alice(Map.of()))).isFalse();
    }

    @Test
    public void userInfoRefused_is401() {
        TokenPrincipal principal = alice(Map.of());
        when(this.userInfo.claims(principal, "token-1"))
                .thenThrow(new InvalidTokenException("refused"));

        assertThatThrownBy(() -> authz(List.of(CLAIM)).mayCreate(new TesTask(), user(principal)))
                .isInstanceOf(UserInfoRefusedException.class);
    }

    @Test
    public void userInfoUnavailable_is503() throws Exception {
        TokenPrincipal principal = alice(Map.of());
        when(this.userInfo.claims(principal, "token-1"))
                .thenThrow(new IssuerUnavailableException("timeout"));

        assertThatThrownBy(() -> authz(List.of(CLAIM)).mayCreate(new TesTask(), user(principal)))
                .isInstanceOf(AuthorisationUnavailableException.class);
        verify(this.decisionService, never()).evaluate(any(), anyString());
    }

    @Test
    public void noAccessToken_is503() {
        this.accessToken = null;

        assertThatThrownBy(
                        () -> authz(List.of(CLAIM)).mayCreate(new TesTask(), user(alice(Map.of()))))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }

    /** Keys without a value are left out of the subject; the service ID only if known */
    @Test
    @SuppressWarnings("unchecked")
    public void request_leavesOutUnknownValues() throws Exception {
        this.serviceId = null;
        TokenPrincipal principal = principal("bob", TokenPrincipal.Kind.USER, null, Map.of());

        authz(List.of()).mayCreate(new TesTask(), user(principal));

        Map<String, Object> request = sent();
        Map<String, Object> properties =
                (Map<String, Object>)
                        ((Map<String, Object>) request.get("subject")).get("properties");
        assertThat(properties.keySet()).containsExactly("iss", "sub", "claims");
        assertThat(request.get("context"))
                .isEqualTo(Map.of("tes", Map.of("profile", "tes-authzen/3")));
    }

    @Test
    public void scope() {
        assertThat(CreateAuthorisation.scope("openid  profile"))
                .contains(List.of("openid", "profile"));
        assertThat(CreateAuthorisation.scope(List.of("a", "b"))).contains(List.of("a", "b"));
        assertThat(CreateAuthorisation.scope(List.of("a", 1))).isEmpty();
        assertThat(CreateAuthorisation.scope(1)).isEmpty();
        assertThat(CreateAuthorisation.scope(null)).isEmpty();
    }

    @Test
    public void currentAccessToken() {
        assertThat(CreateAuthorisation.currentAccessToken()).isNull();
        RequestContextHolder.setRequestAttributes(mock(RequestAttributes.class));
        assertThat(CreateAuthorisation.currentAccessToken()).isNull();
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(CreateAuthorisation.currentAccessToken()).isNull();
        request.addHeader("Authorization", "Bearer abc");
        assertThat(CreateAuthorisation.currentAccessToken()).isEqualTo("abc");
    }

    /** The token isn't sent, nor commands, environments or input contents */
    @Test
    public void request_holdsNoSecrets() throws Exception {
        TokenPrincipal principal = alice(Map.of());
        TesTask task =
                JSON.readValue(
                        "{\"executors\": [{\"image\": \"alpine\", \"command\": [\"echo\","
                                + " \"cmd-secret\"], \"env\": {\"K\": \"env-secret\"}}],"
                                + " \"inputs\": [{\"path\": \"/in\", \"content\":"
                                + " \"content-secret\"}]}",
                        TesTask.class);

        authz(List.of()).mayCreate(task, user(principal));

        String body = JSON.writeValueAsString(sent());
        assertThat(body)
                .doesNotContain("token-1")
                .doesNotContain("secret")
                .contains("\"image\":\"alpine\"");
        verify(this.decisionService).evaluate(any(), anyString());
        verify(this.userInfo, never()).claims(eq(principal), any());
    }
}
