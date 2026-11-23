package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Owner-only task visibility (issue #218), the default of {@code
 * tesk.api.authorisation.task-visibility}: super admins and team admins get, list and cancel only
 * their own tasks, and owners keep full access. The task task-123 is a task of user 123 for the
 * team TEST (see {@link MockUtil}); the list fixture has tasks of users 123 and 124 for the teams
 * TEST and ABC. Group visibility, the previous behaviour, is tested in {@link AuthIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=auth"
        })
public class TaskVisibilityOwnerAuthIT {

    private static final String GROUP = "urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:";

    private static final String SUFFIX = "#perun.elixir-czech.cz";

    @Autowired private MockMvc mvc;

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

    /** User info of the user {@code sub} with the groups (below GA4GH:GA4GH-CAP) */
    private void givenUser(String sub, String... groups) {
        StringBuilder entitlements = new StringBuilder();
        for (String group : groups) {
            if (entitlements.length() > 0) {
                entitlements.append(',');
            }
            entitlements.append('"').append(GROUP).append(group).append(SUFFIX).append('"');
        }
        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \""
                                                + sub
                                                + "\", \"eduperson_entitlement\" : ["
                                                + entitlements
                                                + "]}")));
    }

    @Test
    public void superAdmin_getsAndCancelsNoOtherUsersTask() throws Exception {
        givenUser("xyz", "EBI:ADMIN");
        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        performGetTask(status().isForbidden());
        performCancelTask().andExpect(status().isForbidden());
    }

    /** Nor tasks without a group, e.g. of users authorised by a claim or of service clients */
    @Test
    public void superAdmin_getsAndCancelsNoOtherUsersUngroupedTask() throws Exception {
        givenUser("123", "EBI", "EBI:ADMIN");
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, "user@example.org");

        performGetTask(status().isForbidden());
        performCancelTask().andExpect(status().isForbidden());
    }

    @Test
    public void teamAdmin_getsAndCancelsNoOtherUsersTaskOfTheTeam() throws Exception {
        givenUser("124", "EBI", "EBI:TEST", "EBI:TEST:ADMIN");
        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        performGetTask(status().isForbidden());
        performCancelTask().andExpect(status().isForbidden());
    }

    @Test
    public void owner_getsAndCancelsOwnTask() throws Exception {
        givenUser("123", "EBI", "EBI:TEST");
        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        performGetTask(status().isOk());
        // authorised: the cancellation itself fails, as the task doesn't run
        performCancelTask()
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Job with ID=task-123 has no pods in RUNNING status."));
    }

    @Test
    public void superAdminOwner_getsOwnTask() throws Exception {
        givenUser("123", "EBI:ADMIN");
        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        performGetTask(status().isOk());
    }

    @Test
    public void teamAdminOwner_getsOwnTask() throws Exception {
        givenUser("123", "EBI", "EBI:TEST", "EBI:TEST:ADMIN");
        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        performGetTask(status().isOk());
    }

    @Test
    public void member_getsNoOtherUsersTask() throws Exception {
        givenUser("124", "EBI", "EBI:TEST");
        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        performGetTask(status().isForbidden());
    }

    @Test
    public void superAdmin_listsOwnTasksOnly() throws Exception {
        givenUser("123", "EBI:ADMIN");
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-user-hash%3Da665a45920422f9d417e4867efdc4fb8"
                                        + "&limit=256")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        // the own task-123 and task-125, also if Kubernetes returned the others
        performListTask("task-123", "task-125");
    }

    @Test
    public void teamAdmin_listsOwnTasksOfTheTeamOnly() throws Exception {
        givenUser("124", "EBI:TEST:ADMIN");
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-group-name%20in%20%28TEST%29"
                                        + "%2Ccreator-user-hash%3D6affdae3b3c1aa6aa7689e9b6a7b3225"
                                        + "&limit=256")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        // the own task-124 and task-126, also if Kubernetes returned the others
        performListTask("task-124", "task-126");
    }

    private void performGetTask(ResultMatcher expected) throws Exception {
        for (String view : new String[] {"MINIMAL", "BASIC", "FULL"}) {
            this.mvc
                    .perform(
                            get(TASK_URL + "/{id}?view=" + view, "task-123")
                                    .header("Authorization", "Bearer BAR"))
                    .andExpect(expected);
        }
    }

    private ResultActions performCancelTask() throws Exception {
        return this.mvc.perform(
                post(TASK_URL + "/{id}:cancel", "task-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer BAR"));
    }

    /** Lists the tasks in all views, and expects exactly the tasks with these IDs */
    private void performListTask(String... expectedIds) throws Exception {
        for (String view : new String[] {"MINIMAL", "BASIC", "FULL"}) {
            this.mvc
                    .perform(get(TASK_URL + "?view=" + view).header("Authorization", "Bearer BAR"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tasks.length()").value(expectedIds.length))
                    .andExpect(jsonPath("$.tasks[*].id", containsInAnyOrder(expectedIds)));
        }
    }
}
