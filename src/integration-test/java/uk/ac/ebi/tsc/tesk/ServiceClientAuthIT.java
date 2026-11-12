package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.TestUtils.getFileContentFromResources;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.lang.JoseException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * Integration testing of tokens of allow-listed service clients (issue #153): with {@code
 * tesk.api.authorisation.service-clients.enabled}, a JWT access token of a listed client creates,
 * lists, gets and cancels the client's own tasks without a call to the user info endpoint; other
 * tokens are validated at the user info endpoint as before. The identity provider (discovery, keys
 * and user info), and the Kubernetes API are WireMocked.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=auth",
            "tesk.api.authorisation.service-clients.enabled=true",
            "tesk.api.authorisation.service-clients.issuer=" + ServiceClientAuthIT.ISSUER,
            "tesk.api.authorisation.service-clients.client-ids=gateway,other-gateway",
            "tesk.api.authorisation.service-clients.audience=tesk"
        })
public class ServiceClientAuthIT {

    static final String ISSUER = "http://localhost:8091/oidc/";
    private static final String CLIENT = "gateway";

    private static RsaJsonWebKey key;

    @Autowired private MockMvc mvc;

    @Rule public WireMockRule mockUserInfo = new WireMockRule(8090);

    @Rule public WireMockRule mockIssuer = new WireMockRule(8091);

    @Rule
    public WireMockRule mockKubernetes =
            new WireMockRule(
                    wireMockConfig()
                            .port(9000)
                            .usingFilesUnderDirectory("src/integration-test/resources"));

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {

            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    @BeforeClass
    public static void generateKey() throws JoseException {
        key = RsaJwkGenerator.generateJwk(2048);
        key.setKeyId("key-1");
    }

    @Before
    public void mockIssuer() {
        mockIssuer.givenThat(
                WireMock.get("/oidc/.well-known/openid-configuration")
                        .willReturn(
                                okJson(
                                        "{\"issuer\":\""
                                                + ISSUER
                                                + "\",\"jwks_uri\":\"http://localhost:8091/oidc/jwk\"}")));
        mockIssuer.givenThat(
                WireMock.get("/oidc/jwk").willReturn(okJson(new JsonWebKeySet(key).toJson())));
        // the user info endpoint refuses all tokens unless a test says otherwise
        mockUserInfo.givenThat(WireMock.get("/").willReturn(aResponse().withStatus(401)));
    }

    /** A token of the client credentials grant of {@code client}, valid unless changed */
    private static JwtClaims claims(String client) {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(ISSUER);
        claims.setClaim("client_id", client);
        claims.setSubject(client);
        claims.setAudience("tesk");
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(60);
        claims.setGeneratedJwtId();
        return claims;
    }

    private static String sign(JwtClaims claims) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(key.getPrivateKey());
        jws.setKeyIdHeaderValue(key.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        jws.setHeader("typ", "at+jwt");
        return jws.getCompactSerialization();
    }

    private static String serviceToken() throws JoseException {
        return sign(claims(CLIENT));
    }

    private ResultActions createTask(String token, String path) throws Exception {
        return this.mvc.perform(
                post(TASK_URL)
                        .content(getFileContentFromResources(path))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions listTasks(String token) throws Exception {
        return this.mvc.perform(get(TASK_URL).header("Authorization", "Bearer " + token));
    }

    private ResultActions getTask(String token) throws Exception {
        return this.mvc.perform(
                get(TASK_URL + "/{id}", "task-123").header("Authorization", "Bearer " + token));
    }

    private ResultActions cancelTask(String token) throws Exception {
        return this.mvc.perform(
                post(TASK_URL + "/{id}:cancel", "task-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token));
    }

    private void noUserInfoCalls() {
        mockUserInfo.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    public void serviceClient_createTask_withoutGroup() throws Exception {
        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels['creator-group-name']", absent()))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-user-hash == '"
                                                + CreatorLabels.hash(CLIENT)
                                                + "')]"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.annotations[?(@.creator-user-id == '"
                                                + CLIENT
                                                + "')]"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));
        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        createTask(serviceToken(), "fromTesToK8s_minimal/task.json").andExpect(status().isOk());
        noUserInfoCalls();
    }

    /** No group rules apply to service clients: they can't create tasks for a group */
    @Test
    public void serviceClient_createTask_forGroup() throws Exception {
        createTask(serviceToken(), "fromTesToK8s/task.json").andExpect(status().isForbidden());
        noUserInfoCalls();
    }

    @Test
    public void serviceClient_getList_ownTasksOnly() throws Exception {
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-user-hash%3D"
                                        + CreatorLabels.hash(CLIENT)
                                        + "%2C%21creator-group-name")
                        .willReturn(
                                okJson(
                                        MockUtil.jobList(
                                                MockUtil.taskmaster(null, CLIENT),
                                                MockUtil.taskmaster(null, "other-gateway")))));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        // the own task only, also if Kubernetes returned others
        listTasks(serviceToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1));
        noUserInfoCalls();
    }

    @Test
    public void serviceClient_getOwnTask() throws Exception {
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, CLIENT);

        getTask(serviceToken()).andExpect(status().isOk());
        noUserInfoCalls();
    }

    /** Another listed client's tasks are not the client's */
    @Test
    public void serviceClient_getOthersTask() throws Exception {
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, "other-gateway");

        getTask(serviceToken()).andExpect(status().isForbidden());
    }

    @Test
    public void serviceClient_getGroupTask() throws Exception {
        MockUtil.mockGroupTaskKubernetesResponses(this.mockKubernetes, "TEST", CLIENT);

        getTask(serviceToken()).andExpect(status().isForbidden());
    }

    /** Authorised to cancel the own task; it isn't running, hence 400 */
    @Test
    public void serviceClient_cancelOwnTask() throws Exception {
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, CLIENT);

        cancelTask(serviceToken())
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Job with ID=task-123 has no pods in RUNNING status."));
        noUserInfoCalls();
    }

    @Test
    public void serviceClient_cancelOthersTask() throws Exception {
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, "other-gateway");

        cancelTask(serviceToken()).andExpect(status().isForbidden());
    }

    /** An unlisted client's token goes to the user info endpoint, which refuses it */
    @Test
    public void unlistedClient_isUnauthorized() throws Exception {
        listTasks(sign(claims("unlisted"))).andExpect(status().isUnauthorized());
        mockUserInfo.verify(1, getRequestedFor(urlEqualTo("/")));
    }

    /** An invalid token of a listed client is refused without asking the user info endpoint */
    @Test
    public void expiredServiceToken_isUnauthorized() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setIssuedAt(NumericDate.fromSeconds(1_000_000_000L));
        claims.setExpirationTime(NumericDate.fromSeconds(1_000_003_600L));

        listTasks(sign(claims)).andExpect(status().isUnauthorized());
        noUserInfoCalls();
    }

    @Test
    public void serviceTokenForOtherAudience_isUnauthorized() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setAudience("other-service");

        createTask(sign(claims), "fromTesToK8s_minimal/task.json")
                .andExpect(status().isUnauthorized());
        noUserInfoCalls();
    }

    /** User tokens are validated at the user info endpoint, and group rules apply, as before */
    @Test
    public void userToken_getList_byGroups() throws Exception {
        mockUserInfo.givenThat(
                WireMock.get("/")
                        .withHeader("Authorization", equalTo("Bearer BAR"))
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\", \"eduperson_entitlement\" : ["
                                                + "\"urn:geant:elixir-europe.org:group:elixir:"
                                                + "GA4GH:GA4GH-CAP:EBI:ADMIN#perun.elixir-czech.cz\"]}")));
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        listTasks("BAR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(4));
        mockUserInfo.verify(1, getRequestedFor(urlEqualTo("/")));
    }

    /** A user with no rights, by a user token, can't see the service client's tasks */
    @Test
    public void userToken_getServiceClientsTask() throws Exception {
        mockUserInfo.givenThat(WireMock.get("/").willReturn(okJson("{\"sub\" : \"123\"}")));
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, CLIENT);

        getTask("BAR").andExpect(status().isForbidden());
    }
}
