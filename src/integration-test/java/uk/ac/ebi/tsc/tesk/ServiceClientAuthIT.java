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
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.util.Config;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * Integration testing of service clients (D-392, #227): a listed client's own token (client
 * credentials grant, {@code aud} = its client ID) gets, lists and cancels the client's own tasks,
 * owned by {@code <iss>|<client ID>}, like a user's; creating tasks is denied until a decision
 * service decides it. A client listed with {@code allow-any-audience} is accepted whatever its
 * token's audience. The issuer (discovery, keys) and the Kubernetes API are WireMocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "spring.profiles.active=auth",
            TestIssuer.ISSUER_URL,
            TestIssuer.AUDIENCES,
            TestIssuer.SERVICE_CLIENT,
            "tesk.api.auth.issuers[0].service-clients[1].client-id="
                    + ServiceClientAuthIT.ANY_AUDIENCE_CLIENT,
            "tesk.api.auth.issuers[0].service-clients[1].allow-any-audience=true"
        })
public class ServiceClientAuthIT {

    /** A client whose tokens are accepted whatever their audience (opt-in) */
    static final String ANY_AUDIENCE_CLIENT = "other-gateway";

    private static final String CLIENT = TestIssuer.CLIENT;

    @Autowired private MockMvc mvc;

    @RegisterExtension
    public WireMockExtension mockIssuer =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(TestIssuer.PORT))
                    .failOnUnmatchedRequests(true)
                    .build();

    @RegisterExtension
    public WireMockExtension mockKubernetes =
            WireMockExtension.newInstance()
                    .options(
                            wireMockConfig()
                                    .port(9000)
                                    .usingFilesUnderDirectory("src/integration-test/resources"))
                    .failOnUnmatchedRequests(true)
                    .build();

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    @BeforeEach
    public void stubIssuer() {
        TestIssuer.stub(mockIssuer);
    }

    private static String serviceToken() {
        return TestIssuer.clientToken(CLIENT);
    }

    private static String owner(String clientId) {
        return TestIssuer.owner(clientId);
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

    /** Kubernetes lists no tasks for the hash label of the owner */
    private void mockEmptyList(String owner) {
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-user-hash%3D"
                                        + CreatorLabels.hash(owner)
                                        + "&limit=256")
                        .willReturn(okJson(MockUtil.jobList())));
    }

    /**
     * Creating tasks is denied to service clients too, until a decision service decides it (issue
     * #225); nothing reaches Kubernetes (which fails on unmatched requests)
     */
    @Test
    public void serviceClient_createTask_isDenied() throws Exception {
        createTask(serviceToken(), "fromTesToK8s_minimal/task.json")
                .andExpect(status().isForbidden());
        createTask(serviceToken(), "fromTesToK8s/task.json").andExpect(status().isForbidden());
    }

    @Test
    public void serviceClient_getList_ownTasksOnly() throws Exception {
        V1Job othersTask = MockUtil.taskmaster(owner(ANY_AUDIENCE_CLIENT));
        othersTask.getMetadata().setName("task-456");
        // a task of the client's bare ID, from before the redesign: nobody's
        V1Job bareIdTask = MockUtil.taskmaster(CLIENT);
        bareIdTask.getMetadata().setName("task-789");
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-user-hash%3D"
                                        + CreatorLabels.hash(owner(CLIENT))
                                        + "&limit=256")
                        .willReturn(
                                okJson(
                                        MockUtil.jobList(
                                                MockUtil.taskmaster(owner(CLIENT)),
                                                othersTask,
                                                bareIdTask))));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        // the own task only, also if Kubernetes returned others
        listTasks(serviceToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].id").value("task-123"));
    }

    @Test
    public void serviceClient_getOwnTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, owner(CLIENT));

        getTask(serviceToken()).andExpect(status().isOk());
    }

    /** Another listed client's tasks are not the client's: not found, as missing ones */
    @Test
    public void serviceClient_getOthersTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, owner(ANY_AUDIENCE_CLIENT));

        getTask(serviceToken()).andExpect(status().isNotFound());
    }

    /** Authorised to cancel the own task; it isn't running, hence 400 */
    @Test
    public void serviceClient_cancelOwnTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, owner(CLIENT));

        cancelTask(serviceToken())
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Job with ID=task-123 has no pods in RUNNING status."));
    }

    @Test
    public void serviceClient_cancelOthersTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, owner(ANY_AUDIENCE_CLIENT));

        cancelTask(serviceToken()).andExpect(status().isNotFound());
    }

    /** A client with allow-any-audience is accepted with any audience, also without one */
    @Test
    public void anyAudienceClient_isAccepted() throws Exception {
        mockEmptyList(owner(ANY_AUDIENCE_CLIENT));
        for (String audience : new String[] {"other-service", null}) {
            JwtClaims claims = TestIssuer.clientClaims(ANY_AUDIENCE_CLIENT);
            if (audience == null) {
                claims.unsetClaim("aud");
            } else {
                claims.setAudience(audience);
            }
            listTasks(TestIssuer.sign(claims))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tasks.length()").value(0));
        }
    }

    /** ... but only its own tokens: a user's token for another audience is still refused */
    @Test
    public void anyAudienceClient_userTokens_needTheAudience() throws Exception {
        JwtClaims claims = TestIssuer.userClaims("user-1");
        claims.setClaim("client_id", ANY_AUDIENCE_CLIENT);
        claims.setAudience("other-service");
        listTasks(TestIssuer.sign(claims)).andExpect(status().isUnauthorized());
    }

    /** A user doesn't get the service client's tasks: not found */
    @Test
    public void userToken_getServiceClientsTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, owner(CLIENT));

        getTask(TestIssuer.userToken("123")).andExpect(status().isNotFound());
    }
}
