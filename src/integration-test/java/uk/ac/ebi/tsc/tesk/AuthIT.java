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
 * Integration testing of security without the group model (issue #225): users are authenticated at
 * the OIDC user info endpoint, and get, list and cancel only the tasks they created, whatever
 * groups the user info names; creating tasks is denied to everyone until a decision service decides
 * it. Kubernetes API and OIDC user info endpoint are WireMocked; both fail on unmatched requests,
 * so a request that should not reach Kubernetes fails the test if it does. The full caller x
 * operation matrix is in the access matrix suite (accessmatrix).
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=auth"
        })
public class AuthIT {

    /** Groups that gave every right in the group model (super admin) */
    private static final String[] ADMIN_GROUPS = {
        "GA4GH:GA4GH-CAP:EBI", "GA4GH:GA4GH-CAP:EBI:ADMIN", "GA4GH:GA4GH-CAP:EBI:TEST:ADMIN"
    };

    @Autowired private MockMvc mvc;

    @Autowired private UserDetailsService userDetailsService;

    @RegisterExtension
    public WireMockExtension mockElixir =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(8090))
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

    private void userInfo(String sub, String... groups) {
        StringBuilder json = new StringBuilder("{\"sub\" : \"" + sub + "\", ");
        json.append("\"eduperson_entitlement\" : [");
        for (int i = 0; i < groups.length; i++) {
            json.append(i > 0 ? "," : "")
                    .append("\"urn:geant:elixir-europe.org:group:elixir:")
                    .append(groups[i])
                    .append("#perun.elixir-czech.cz\"");
        }
        json.append("]}");
        mockElixir.givenThat(WireMock.get("/").willReturn(okJson(json.toString())));
    }

    private ResultActions createTask(String path) throws Exception {
        return this.mvc.perform(
                post(TASK_URL)
                        .content(getFileContentFromResources(path))
                        .header("Authorization", "Bearer BAR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON));
    }

    private void expectGetTask(int status) throws Exception {
        for (String view : new String[] {"MINIMAL", "BASIC", "FULL"}) {
            this.mvc
                    .perform(
                            get(TASK_URL + "/{id}?view=" + view, "task-123")
                                    .header("Authorization", "Bearer BAR"))
                    .andExpect(status().is(status));
        }
    }

    private ResultActions cancelTask() throws Exception {
        return this.mvc.perform(
                post(TASK_URL + "/{id}:cancel", "task-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer BAR"));
    }

    /** Kubernetes lists the jobs with the hash label of {@code userId} */
    private void mockList(String userId) {
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-user-hash%3D"
                                        + CreatorLabels.hash(userId)
                                        + "&limit=256")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);
    }

    private void expectList(int expectedLength) throws Exception {
        for (String view : new String[] {"MINIMAL", "BASIC", "FULL"}) {
            this.mvc
                    .perform(get(TASK_URL + "?view=" + view).header("Authorization", "Bearer BAR"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tasks.length()").value(expectedLength));
        }
    }

    /**
     * Creating tasks is denied to every user, with or without groups, also for a group in the
     * {@code GROUP_NAME} tag; nothing reaches Kubernetes
     */
    @Test
    public void createTask_isDeniedToEveryone() throws Exception {
        userInfo("123", ADMIN_GROUPS);
        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isForbidden());
        createTask("fromTesToK8s/task.json").andExpect(status().isForbidden());
        userInfo("124");
        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isForbidden());
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

    /** The creator gets the task, without any group */
    @Test
    public void creator_getTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, "123");
        userInfo("123");
        expectGetTask(200);
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=BASIC", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(jsonPath("$.logs[0].metadata.USER_ID").value("123"))
                .andExpect(jsonPath("$.logs[0].metadata.GROUP_NAME").doesNotExist());
    }

    /**
     * Anyone else, also a member, manager or admin of the task's group in the group model, doesn't
     * find the task: 404, as for a missing one
     */
    @Test
    public void nonCreator_getTask_isNotFound() throws Exception {
        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);
        userInfo("124", "GA4GH:GA4GH-CAP:EBI", "GA4GH:GA4GH-CAP:EBI:TEST");
        expectGetTask(404);
        userInfo("124", ADMIN_GROUPS);
        expectGetTask(404);
    }

    /** IDs that the old label encoding merged are told apart (issue #119) */
    @Test
    public void similarIds_getTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, "a@b");
        userInfo("a_b");
        expectGetTask(404);
        userInfo("a@b");
        expectGetTask(200);
    }

    /** Tasks with only the old label belong to nobody, not even admins (issues #119, #225) */
    @Test
    public void legacyLabel_getTask_isNotFound() throws Exception {
        MockUtil.mockLegacyTaskKubernetesResponses(this.mockKubernetes, "123");
        userInfo("123");
        expectGetTask(404);
        userInfo("124", ADMIN_GROUPS);
        expectGetTask(404);
    }

    /** Authorised to cancel the own task; it isn't running, hence 400 */
    @Test
    public void creator_cancelTask() throws Exception {
        MockUtil.mockOwnTaskKubernetesResponses(this.mockKubernetes, "123");
        userInfo("123");
        cancelTask()
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Job with ID=task-123 has no pods in RUNNING status."));
    }

    /** Anyone else, also an admin, doesn't find the task, and nothing is changed */
    @Test
    public void nonCreator_cancelTask_isNotFound() throws Exception {
        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);
        userInfo("124", ADMIN_GROUPS);
        cancelTask().andExpect(status().isNotFound());
        mockKubernetes.verify(0, patchRequestedFor(anyUrl()));
        mockKubernetes.verify(0, putRequestedFor(anyUrl()));
        mockKubernetes.verify(0, deleteRequestedFor(anyUrl()));
    }

    /**
     * The list holds the user's own tasks only (task-123 and task-125), also if Kubernetes returned
     * others, and also for users that were admins of all groups
     */
    @Test
    public void getList_ownTasksOnly() throws Exception {
        mockList("123");
        userInfo("123");
        expectList(2);
        userInfo("123", ADMIN_GROUPS);
        expectList(2);
    }

    /** A user without tasks gets an empty list, also with groups */
    @Test
    public void getList_noOwnTasks() throws Exception {
        mockList("999");
        userInfo("999", ADMIN_GROUPS);
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
        userInfo("123");
        this.mvc
                .perform(get(SERVICE_INFO_URL).header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
    }

    @Test
    public void public_serviceInfo_withInvalidToken_isUnauthorized() throws Exception {
        mockElixir.givenThat(WireMock.get("/").willReturn(unauthorized()));

        this.mvc
                .perform(get(SERVICE_INFO_URL).header("Authorization", "Bearer BAR"))
                .andExpect(status().isUnauthorized());
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
