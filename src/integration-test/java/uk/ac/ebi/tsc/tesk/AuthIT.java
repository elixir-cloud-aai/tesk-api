package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.TestUtils.getFileContentFromResources;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SERVICE_INFO_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * Integration testing of security without the group model (issue #225) with tokens validated by
 * TESK API itself (#227): users get, list and cancel only the tasks they created, identified by
 * {@code <iss>|<sub>} of their tokens, whatever groups they have; creating tasks is denied to
 * everyone until a decision service decides it. Tasks from before the redesign, owned by a bare
 * {@code sub}, are nobody's. The issuer (discovery, keys) and the Kubernetes API are WireMocked;
 * both fail on unmatched requests, so a request that should not reach Kubernetes fails the test if
 * it does. The full caller x operation matrix is in the access matrix suite (accessmatrix).
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {"spring.profiles.active=auth", TestIssuer.ISSUER_URL, TestIssuer.AUDIENCES})
public class AuthIT {

    @Autowired private MockMvc mvc;

    @Autowired private UserDetailsService userDetailsService;

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

    /** The token of the requests */
    private String token;

    @BeforeEach
    public void stubIssuer() {
        TestIssuer.stub(mockIssuer);
    }

    /** The requests are sent with a token of {@code sub} */
    private void as(String sub) {
        this.token = TestIssuer.userToken(sub);
    }

    private ResultActions createTask(String path) throws Exception {
        return this.mvc.perform(
                post(TASK_URL)
                        .content(getFileContentFromResources(path))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON));
    }

    private void expectGetTask(int status) throws Exception {
        for (String view : new String[] {"MINIMAL", "BASIC", "FULL"}) {
            this.mvc
                    .perform(
                            get(TASK_URL + "/{id}?view=" + view, "task-123")
                                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().is(status));
        }
    }

    private ResultActions cancelTask() throws Exception {
        return this.mvc.perform(
                post(TASK_URL + "/{id}:cancel", "task-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token));
    }

    /**
     * Kubernetes lists, for the hash label of {@code owner}, the jobs of list/taskmasters.json,
     * those of user 123 as created by {@code creator} (those of 124 stay someone else's)
     */
    private void mockList(String owner, String creator) throws Exception {
        String json =
                Files.readString(
                                Path.of(
                                        "src/integration-test/resources/__files/list/taskmasters.json"))
                        .replace(CreatorLabels.hash("123"), CreatorLabels.hash(creator))
                        .replace(
                                "\"creator-user-id\": \"123\"",
                                "\"creator-user-id\": \"" + creator + "\"");
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-user-hash%3D"
                                        + CreatorLabels.hash(owner)
                                        + "&limit=256")
                        .willReturn(okJson(json)));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);
    }

    private void expectList(int expectedLength) throws Exception {
        for (String view : new String[] {"MINIMAL", "BASIC", "FULL"}) {
            this.mvc
                    .perform(
                            get(TASK_URL + "?view=" + view)
                                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tasks.length()").value(expectedLength));
        }
    }

    /** Creating tasks is denied to every user; nothing reaches Kubernetes */
    @Test
    public void createTask_isDeniedToEveryone() throws Exception {
        as("123");
        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isForbidden());
        createTask("fromTesToK8s/task.json").andExpect(status().isForbidden());
        mockKubernetes.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    public void unauthenticated_createTask() throws Exception {
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources("fromTesToK8s/task.json"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void unauthenticated_getTask() throws Exception {
        this.mvc.perform(get(TASK_URL + "/{id}", 123)).andExpect(status().isUnauthorized());
    }

    @Test
    public void unauthenticated_cancelTask() throws Exception {
        this.mvc.perform(post(TASK_URL + "/{id}:cancel", 123)).andExpect(status().isUnauthorized());
    }

    @Test
    public void unauthenticated_getList() throws Exception {
        this.mvc.perform(get(TASK_URL)).andExpect(status().isUnauthorized());
    }

    /** The creator gets the task; its log names the owner ID {@code <iss>|<sub>} */
    @Test
    public void creator_getTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, TestIssuer.owner("123"));
        as("123");
        expectGetTask(200);
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=BASIC", "task-123")
                                .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.logs[0].metadata.USER_ID").value(TestIssuer.owner("123")))
                .andExpect(jsonPath("$.logs[0].metadata.GROUP_NAME").doesNotExist());
    }

    /** Anyone else doesn't find the task: 404, as for a missing one */
    @Test
    public void nonCreator_getTask_isNotFound() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, TestIssuer.owner("123"));
        as("124");
        expectGetTask(404);
    }

    /** IDs that the old label encoding merged are told apart (issue #119) */
    @Test
    public void similarIds_getTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, TestIssuer.owner("a@b"));
        as("a_b");
        expectGetTask(404);
        as("a@b");
        expectGetTask(200);
    }

    /** Tasks with only the old label belong to nobody (issues #119, #225) */
    @Test
    public void legacyLabel_getTask_isNotFound() throws Exception {
        MockUtil.mockLegacyTaskKubernetesResponses(this.mockKubernetes, "123");
        as("123");
        expectGetTask(404);
    }

    /**
     * Tasks from before the redesign, owned by the bare {@code sub} of their creator, are nobody's
     * now (#227, D-409): their creator doesn't get or cancel them (404), nor finds them in the list
     */
    @Test
    public void bareSubTask_isNobodys() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, "123");
        as("123");
        expectGetTask(404);
        cancelTask().andExpect(status().isNotFound());
        mockKubernetes.verify(0, patchRequestedFor(anyUrl()));
        // also if Kubernetes listed them for the owner's selector
        mockList(TestIssuer.owner("123"), "123");
        expectList(0);
    }

    /** Authorised to cancel the own task; it isn't running, hence 400 */
    @Test
    public void creator_cancelTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, TestIssuer.owner("123"));
        as("123");
        cancelTask()
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Job with ID=task-123 has no pods in RUNNING status."));
    }

    /** Anyone else doesn't find the task, and nothing is changed */
    @Test
    public void nonCreator_cancelTask_isNotFound() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, TestIssuer.owner("123"));
        as("124");
        cancelTask().andExpect(status().isNotFound());
        mockKubernetes.verify(0, patchRequestedFor(anyUrl()));
        mockKubernetes.verify(0, putRequestedFor(anyUrl()));
        mockKubernetes.verify(0, deleteRequestedFor(anyUrl()));
    }

    /**
     * The list holds the user's own tasks only (task-123 and task-125), also if Kubernetes returned
     * others
     */
    @Test
    public void getList_ownTasksOnly() throws Exception {
        mockList(TestIssuer.owner("123"), TestIssuer.owner("123"));
        as("123");
        expectList(2);
    }

    /** A user without tasks gets an empty list */
    @Test
    public void getList_noOwnTasks() throws Exception {
        mockList(TestIssuer.owner("999"), TestIssuer.owner("123"));
        as("999");
        expectList(0);
    }

    @Test
    public void anybody_can_see_serviceInfo() throws Exception {
        this.mvc.perform(get(SERVICE_INFO_URL)).andExpect(status().isOk());
    }

    /**
     * With a trailing slash, which the app ignores (TrailingSlashConfiguration), service info needs
     * a token, as before: the public paths are checked as they are sent
     */
    @Test
    public void serviceInfo_withTrailingSlash_needsToken() throws Exception {
        this.mvc.perform(get(SERVICE_INFO_URL + "/")).andExpect(status().isUnauthorized());
    }

    @Test
    public void public_serviceInfo_withValidToken_isOk() throws Exception {
        as("123");
        this.mvc
                .perform(get(SERVICE_INFO_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** Public service info ignores any token, also an invalid one, without asking the issuer */
    @Test
    public void public_serviceInfo_withInvalidToken_isOk() throws Exception {
        for (String header : new String[] {"Bearer BAR", "Bearer a b", "Bearer "}) {
            this.mvc
                    .perform(get(SERVICE_INFO_URL).header("Authorization", header))
                    .andExpect(status().isOk());
        }
        mockIssuer.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * Spring Boot's in-memory user store (with a generated password) is replaced by one without
     * users
     */
    @Test
    public void userStore_hasNoUsers() {
        assertThat(userDetailsService).isNotInstanceOf(InMemoryUserDetailsManager.class);
        assertThat(userDetailsService.loadUserByUsername("user")).isNull();
    }
}
