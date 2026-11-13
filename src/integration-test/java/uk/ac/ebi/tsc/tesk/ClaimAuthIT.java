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

/**
 * Integration testing of authorisation without groups (issue #78): with {@code
 * tesk.api.authorisation.claim.enabled}, users with one of the required values of the configured
 * claim may create tasks without a group and see only their own tasks; group members and admins
 * keep their rights. Kubernetes API and OIDC userInfo endpoint are WireMocked.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=auth",
            "tesk.api.authorisation.claim.enabled=true",
            "tesk.api.authorisation.claim.name=eduperson_assurance",
            "tesk.api.authorisation.claim.values="
                    + "https://refeds.org/assurance/IAP/medium,https://refeds.org/assurance/IAP/high"
        })
public class ClaimAuthIT {

    private static final String GROUP_PREFIX = "urn:geant:elixir-europe.org:group:elixir:";
    private static final String GROUP_SUFFIX = "#perun.elixir-czech.cz";

    @Autowired private MockMvc mvc;

    @Rule public WireMockRule mockElixir = new WireMockRule(8090);

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

    /** User info of the user {@code sub} with the claim's value (or none) and groups */
    private void userInfo(String sub, String assurance, String... groups) {
        StringBuilder json = new StringBuilder("{\"sub\" : \"" + sub + "\"");
        if (assurance != null) {
            json.append(", \"eduperson_assurance\" : [\"https://refeds.org/assurance/IAP/low\", \"")
                    .append(assurance)
                    .append("\"]");
        }
        json.append(", \"eduperson_entitlement\" : [");
        for (int i = 0; i < groups.length; i++) {
            json.append(i > 0 ? ", " : "")
                    .append("\"")
                    .append(GROUP_PREFIX)
                    .append(groups[i])
                    .append(GROUP_SUFFIX)
                    .append("\"");
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

    private ResultActions listTasks() throws Exception {
        return this.mvc.perform(get(TASK_URL).header("Authorization", "Bearer BAR"));
    }

    private ResultActions getTask() throws Exception {
        return this.mvc.perform(
                get(TASK_URL + "/{id}", "task-123").header("Authorization", "Bearer BAR"));
    }

    private ResultActions cancelTask() throws Exception {
        return this.mvc.perform(
                post(TASK_URL + "/{id}:cancel", "task-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer BAR"));
    }

    private void mockUngroupedTask(String userId) {
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, userId);
    }

    private void mockCreateTask(String groupLabel) {
        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                groupLabel == null
                                        ? matchingJsonPath(
                                                "$.metadata.labels['creator-group-name']", absent())
                                        : matchingJsonPath(
                                                "$.metadata.labels[?(@.creator-group-name == '"
                                                        + groupLabel
                                                        + "')]"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));
        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));
    }

    @Test
    public void claimUser_createTask_withoutGroup() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/medium");
        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels['creator-group-name']", absent()))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-user-hash == 'd159ef624ed86697b4f1f3ff086aacdd')]"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));
        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isOk());
    }

    /** Any one of the required values is enough */
    @Test
    public void claimUserOtherValue_createTask() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/high");
        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));
        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isOk());
    }

    /** A user authorised by the claim only can't create tasks for a group */
    @Test
    public void claimUser_createTask_forGroup() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/medium");

        createTask("fromTesToK8s/task.json").andExpect(status().isForbidden());
    }

    @Test
    public void claimAbsent_createTask() throws Exception {
        userInfo("user@example.org", null);

        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isForbidden());
    }

    @Test
    public void claimWrongValue_createTask() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/medium/other");

        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isForbidden());
    }

    @Test
    public void claimAbsent_getList() throws Exception {
        userInfo("user@example.org", null);

        listTasks().andExpect(status().isForbidden());
    }

    @Test
    public void claimWrongValue_getList() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/low");

        listTasks().andExpect(status().isForbidden());
    }

    /** A user authorised by the claim lists only their own tasks without a group */
    @Test
    public void claimUser_getList() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/medium");
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-user-hash%3Dd159ef624ed86697b4f1f3ff086aacdd%2C%21creator-group-name"
                                        + "&limit=256")
                        .willReturn(
                                okJson(
                                        MockUtil.jobList(
                                                MockUtil.taskmaster(null, "user@example.org"),
                                                MockUtil.taskmaster(null, "user_example.org")))));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        // the own task only, also if Kubernetes returned others
        listTasks().andExpect(status().isOk()).andExpect(jsonPath("$.tasks.length()").value(1));
    }

    /** Admins still list all tasks, with or without the claim */
    @Test
    public void superAdmin_getList() throws Exception {
        for (String assurance : new String[] {null, "https://refeds.org/assurance/IAP/medium"}) {
            userInfo("123", assurance, "GA4GH:GA4GH-CAP:EBI:ADMIN");
            mockKubernetes.givenThat(
                    WireMock.get(
                                    "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                            + "&limit=256")
                            .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
            MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

            listTasks().andExpect(status().isOk()).andExpect(jsonPath("$.tasks.length()").value(4));
        }
    }

    /** Group members keep their rules, also with the claim */
    @Test
    public void member_getList() throws Exception {
        for (String assurance : new String[] {null, "https://refeds.org/assurance/IAP/medium"}) {
            userInfo("123", assurance, "GA4GH:GA4GH-CAP:EBI", "GA4GH:GA4GH-CAP:EBI:TEST");
            mockKubernetes.givenThat(
                    WireMock.get(
                                    "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                            + "%2Ccreator-group-name%20in%20%28TEST%29%2Ccreator-user-hash%3Da665a45920422f9d417e4867efdc4fb8"
                                            + "&limit=256")
                            .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
            MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

            listTasks().andExpect(status().isOk()).andExpect(jsonPath("$.tasks.length()").value(2));
        }
    }

    @Test
    public void claimUser_getOwnTask() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/medium");
        mockUngroupedTask("user@example.org");

        getTask().andExpect(status().isOk());
    }

    @Test
    public void claimUser_getOthersTask() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/medium");
        mockUngroupedTask("someone_else");

        getTask().andExpect(status().isForbidden());
    }

    /** A task of a group stays hidden from its creator once only the claim authorises them */
    @Test
    public void claimUser_getOwnGroupTask() throws Exception {
        userInfo("123", "https://refeds.org/assurance/IAP/medium");
        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        getTask().andExpect(status().isForbidden());
    }

    @Test
    public void claimAbsent_getOwnTask() throws Exception {
        userInfo("user@example.org", null);
        mockUngroupedTask("user@example.org");

        getTask().andExpect(status().isForbidden());
    }

    @Test
    public void superAdmin_getUngroupedTask() throws Exception {
        userInfo("123", null, "GA4GH:GA4GH-CAP:EBI:ADMIN");
        mockUngroupedTask("user@example.org");

        getTask().andExpect(status().isOk());
    }

    /** Authorised to cancel the own task; it isn't running, hence 400 */
    @Test
    public void claimUser_cancelOwnTask() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/medium");
        mockUngroupedTask("user@example.org");

        cancelTask()
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Job with ID=task-123 has no pods in RUNNING status."));
    }

    /** Group members with the claim create tasks for their group, as without it */
    @Test
    public void memberWithClaim_createTask() throws Exception {
        userInfo(
                "123",
                "https://refeds.org/assurance/IAP/medium",
                "GA4GH:GA4GH-CAP:EBI",
                "GA4GH:GA4GH-CAP:EBI:TEST");
        mockCreateTask("TEST");

        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isOk());
    }

    /** A claim user who is a member of the chosen group may create tasks for it */
    @Test
    public void memberWithClaim_createTask_forOwnGroup() throws Exception {
        userInfo(
                "123",
                "https://refeds.org/assurance/IAP/medium",
                "GA4GH:GA4GH-CAP:EBI",
                "GA4GH:GA4GH-CAP:EBI:ABC");
        mockCreateTask("ABC");
        // the full task asks for memory, so the limits are read
        mockKubernetes.givenThat(
                WireMock.get("/api/v1/namespaces/default/limitranges")
                        .willReturn(okJson("{\"metadata\":{\"name\":\"core-resource-limits\"}}")));

        createTask("fromTesToK8s/task.json").andExpect(status().isOk());
    }

    /** Super admins create tasks without a group, with or without the claim */
    @Test
    public void superAdmin_createTask() throws Exception {
        for (String assurance : new String[] {null, "https://refeds.org/assurance/IAP/medium"}) {
            userInfo("123", assurance, "GA4GH:GA4GH-CAP:EBI", "GA4GH:GA4GH-CAP:EBI:ADMIN");
            mockCreateTask(null);

            createTask("fromTesToK8s_minimal/task.json").andExpect(status().isOk());
        }
    }

    @Test
    public void claimWrongValue_getOwnTask() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/low");
        mockUngroupedTask("user@example.org");

        getTask().andExpect(status().isForbidden());
    }

    @Test
    public void claimAbsentOrWrong_cancelOwnTask() throws Exception {
        for (String assurance : new String[] {null, "https://refeds.org/assurance/IAP/low"}) {
            userInfo("user@example.org", assurance);
            mockUngroupedTask("user@example.org");

            cancelTask().andExpect(status().isForbidden());
        }
    }

    /** Super admins may cancel tasks of claim users (it isn't running, hence 400) */
    @Test
    public void superAdmin_cancelUngroupedTask() throws Exception {
        userInfo("123", null, "GA4GH:GA4GH-CAP:EBI", "GA4GH:GA4GH-CAP:EBI:ADMIN");
        mockUngroupedTask("user@example.org");

        cancelTask()
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Job with ID=task-123 has no pods in RUNNING status."));
    }

    @Test
    public void claimUser_cancelOthersTask() throws Exception {
        userInfo("user@example.org", "https://refeds.org/assurance/IAP/medium");
        mockUngroupedTask("someone_else");

        cancelTask().andExpect(status().isForbidden());
    }
}
