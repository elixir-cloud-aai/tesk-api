package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.TestUtils.getFileContentFromResources;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.util.Config;
import java.util.Arrays;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * Integration testing of the decision service (tesk.api.authz, profile tes-authzen/1): the claim
 * rule as an AND gate, and one AuthZEN evaluation per decision instead of the group model. The
 * Kubernetes API, the user info endpoint and the decision service are WireMocked; the cases are
 * numbered as in the shared conformance suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=auth",
            "tesk.api.authz.issuer=" + DecisionServiceIT.ISS,
            "tesk.api.authz.required-claim.name=eduperson_entitlement",
            "tesk.api.authz.required-claim.prefixes=" + DecisionServiceIT.VO + ":",
            "tesk.api.authz.decision-service.url=http://localhost:8091/access/v1/evaluation",
            "tesk.api.authz.decision-service.timeout=1",
            "tesk.api.authz.decision-service.claims=eduperson_entitlement",
            "tesk.api.service-info.location=file:src/main/resources/service-info.yaml"
        })
public class DecisionServiceIT {

    static final String ISS = "https://idp.example.org";
    static final String VO = "urn:geant:lifescience-ri.eu:group:example-vo";
    private static final String SUBGROUP = VO + ":analysts#login.aai.lifescience-ri.eu";
    private static final String ALICE = "alice-sub";
    private static final String EVALUATION = "/access/v1/evaluation";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;

    @RegisterExtension
    public WireMockExtension mockUserInfo =
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

    @RegisterExtension
    public WireMockExtension mockPdp =
            WireMockExtension.newInstance().options(wireMockConfig().port(8091)).build();

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    /** User info of alice with the entitlement (or with another one) */
    private void userInfo(String entitlement) {
        mockUserInfo.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\": \""
                                                + ALICE
                                                + "\", \"email\": \"alice@example.org\","
                                                + " \"eduperson_entitlement\": [\""
                                                + entitlement
                                                + "\"]}")));
    }

    /** The decision service answers this decision for the action (on the resource ID, if given) */
    private void decide(String action, String resourceId, boolean decision) {
        com.github.tomakehurst.wiremock.client.MappingBuilder request =
                WireMock.post(EVALUATION)
                        .withRequestBody(matchingJsonPath("$.action.name", equalTo(action)));
        if (resourceId != null) {
            request =
                    request.withRequestBody(matchingJsonPath("$.resource.id", equalTo(resourceId)));
        }
        mockPdp.givenThat(request.willReturn(okJson("{\"decision\": " + decision + "}")));
    }

    private List<JsonNode> evaluations() {
        return mockPdp.findAll(postRequestedFor(urlEqualTo(EVALUATION))).stream()
                .map(LoggedRequest::getBodyAsString)
                .map(MAPPER::readTree)
                .collect(java.util.stream.Collectors.toList());
    }

    private ResultActions createTask(String path) throws Exception {
        return this.mvc.perform(
                post(TASK_URL)
                        .content(getFileContentFromResources(path))
                        .header("Authorization", "Bearer BAR")
                        .header("X-Request-ID", "client-chosen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions getTask(String id) throws Exception {
        return this.mvc.perform(
                get(TASK_URL + "/{id}", id)
                        .param("view", "BASIC")
                        .header("Authorization", "Bearer BAR"));
    }

    private ResultActions cancelTask(String id) throws Exception {
        return this.mvc.perform(
                post(TASK_URL + "/{id}:cancel", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer BAR"));
    }

    private ResultActions listTasks(String pageToken) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                get(TASK_URL).param("page_size", "2").header("Authorization", "Bearer BAR");
        if (pageToken != null) {
            request = request.param("page_token", pageToken);
        }
        return this.mvc.perform(request);
    }

    private void taskMissing(String id) {
        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs/" + id)
                        .willReturn(aResponse().withStatus(404)));
    }

    private void mockCreate() {
        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.annotations['owner-id']",
                                        equalTo(ISS + "|" + ALICE)))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));
        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));
    }

    /** Cases 7, 18, 19: the request as specified; owner stamped; no token or client values */
    @Test
    public void case07_create() throws Exception {
        userInfo(SUBGROUP);
        decide("CreateTask", "own", true);
        mockCreate();

        createTask("fromTesToK8s_minimal/task.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-fe99716a"));

        List<JsonNode> evaluations = evaluations();
        assertThat(evaluations).hasSize(1);
        JsonNode request = evaluations.get(0);
        assertThat(request.get("subject"))
                .isEqualTo(
                        MAPPER.readTree(
                                "{\"type\": \"user\", \"id\": \""
                                        + ISS
                                        + "|"
                                        + ALICE
                                        + "\", \"properties\": {\"iss\": \""
                                        + ISS
                                        + "\", \"sub\": \""
                                        + ALICE
                                        + "\", \"claims\": {\"eduperson_entitlement\": [\""
                                        + SUBGROUP
                                        + "\"]}}}"));
        assertThat(request.get("action"))
                .isEqualTo(MAPPER.readTree("{\"name\": \"CreateTask\", \"properties\": {}}"));
        assertThat(request.at("/resource/type").asString()).isEqualTo("tes_task_set");
        assertThat(request.at("/resource/properties/task/executors/0/image").isString()).isTrue();
        assertThat(request.get("context"))
                .isEqualTo(
                        MAPPER.readTree(
                                "{\"tes\": {\"profile\": \"tes-authzen/1\", \"service_id\":"
                                        + " \"uk.ac.ebi.tsc.tesk\"}}"));
        LoggedRequest logged = mockPdp.findAll(postRequestedFor(urlEqualTo(EVALUATION))).get(0);
        assertThat(logged.getHeader("X-Request-ID"))
                .matches("[0-9a-f-]{36}")
                .isNotEqualTo("client-chosen");
        assertThat(logged.getHeader("Authorization")).isNull();
        assertThat(logged.getBodyAsString()).doesNotContain("BAR").doesNotContain("alice@");
    }

    /** Case 8 and 17: the rule passes, the decision service denies: 403, nothing created */
    @Test
    public void case08_createDenied() throws Exception {
        userInfo(SUBGROUP);
        decide("CreateTask", null, false);

        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isForbidden());
    }

    /** Case 5 and 16: the claim rule fails: 403 on every action, the decision service not asked */
    @Test
    public void case05_ruleFails() throws Exception {
        userInfo(VO + "x#login.aai.lifescience-ri.eu");
        decide("CreateTask", null, true);

        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isForbidden());
        getTask("task-123").andExpect(status().isForbidden());
        cancelTask("task-123").andExpect(status().isForbidden());
        listTasks(null).andExpect(status().isForbidden());
        assertThat(evaluations()).isEmpty();
    }

    /** Case 15: any failure of the decision service gives 503, nothing executed */
    @Test
    public void case15_decisionServiceFails() throws Exception {
        userInfo(SUBGROUP);
        for (String answer :
                Arrays.asList("{\"decision\": \"true\"}", "{}", "not json", "{\"x\": 1}")) {
            mockPdp.givenThat(WireMock.post(EVALUATION).willReturn(okJson(answer)));
            createTask("fromTesToK8s_minimal/task.json")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("Authorisation service unavailable"));
        }
        mockPdp.givenThat(WireMock.post(EVALUATION).willReturn(aResponse().withStatus(500)));
        createTask("fromTesToK8s_minimal/task.json").andExpect(status().isServiceUnavailable());
        mockPdp.givenThat(
                WireMock.post(EVALUATION)
                        .willReturn(okJson("{\"decision\": true}").withFixedDelay(2000)));
        listTasks(null).andExpect(status().isServiceUnavailable());
    }

    /** Case 9: the decision service lets alice get bob's task (owner derived from the creator) */
    @Test
    public void case09_getForeignTask() throws Exception {
        userInfo(SUBGROUP);
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, "bob");
        decide("GetTask", "task-123", true);

        getTask("task-123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-123"));

        JsonNode request = evaluations().get(0);
        assertThat(request.get("action"))
                .isEqualTo(
                        MAPPER.readTree(
                                "{\"name\": \"GetTask\", \"properties\": {\"view\": \"BASIC\"}}"));
        assertThat(request.at("/resource/properties/owner").asString()).isEqualTo(ISS + "|bob");
        assertThat(request.at("/resource/properties/state").isString()).isTrue();
        assertThat(request.at("/resource/properties/tags").isObject()).isTrue();
    }

    /** Case 10: denied get and cancel are 404, the task unchanged */
    @Test
    public void case10_getAndCancelDenied() throws Exception {
        userInfo(SUBGROUP);
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, "bob");
        decide("GetTask", null, false);
        decide("CancelTask", null, false);

        getTask("task-123").andExpect(status().isNotFound());
        cancelTask("task-123").andExpect(status().isNotFound());
    }

    /** Case 10b: get allowed, cancel denied: 403 */
    @Test
    public void case10b_cancelDeniedButReadable() throws Exception {
        userInfo(SUBGROUP);
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, "bob");
        decide("GetTask", null, true);
        decide("CancelTask", null, false);

        cancelTask("task-123").andExpect(status().isForbidden());
        assertThat(evaluations().get(1).get("action"))
                .isEqualTo(MAPPER.readTree("{\"name\": \"GetTask\", \"properties\": {}}"));
    }

    /** The cancellation is allowed: the task is completed, so it can't be cancelled (400) */
    @Test
    public void cancelAllowed() throws Exception {
        userInfo(SUBGROUP);
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, ALICE);
        decide("CancelTask", null, true);

        cancelTask("task-123").andExpect(status().isBadRequest());
        assertThat(evaluations()).hasSize(1);
    }

    /** Case 11: a missing task is asked with no properties, then 404 whatever the decision */
    @Test
    public void case11_getMissingTask() throws Exception {
        userInfo(SUBGROUP);
        taskMissing("gone");
        decide("GetTask", null, true);

        getTask("gone").andExpect(status().isNotFound());
        assertThat(evaluations().get(0).get("resource"))
                .isEqualTo(
                        MAPPER.readTree(
                                "{\"type\": \"tes_task\", \"id\": \"gone\", \"properties\": {}}"));
    }

    /** Case 11b: decision service down: 503 for missing and existing tasks alike */
    @Test
    public void case11b_downForMissingAndExisting() throws Exception {
        userInfo(SUBGROUP);
        taskMissing("gone");
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, ALICE);
        mockPdp.givenThat(WireMock.post(EVALUATION).willReturn(aResponse().withStatus(503)));

        getTask("gone").andExpect(status().isServiceUnavailable());
        getTask("task-123").andExpect(status().isServiceUnavailable());
    }

    /** Case 11c: cancelling a missing task costs the same two calls as a denied one, then 404 */
    @Test
    public void case11c_cancelMissingTask() throws Exception {
        userInfo(SUBGROUP);
        taskMissing("gone");
        decide("CancelTask", null, true);
        decide("GetTask", null, true);

        cancelTask("gone").andExpect(status().isNotFound());
        List<JsonNode> evaluations = evaluations();
        assertThat(evaluations).hasSize(2);
        assertThat(evaluations.get(0).at("/action/name").asString()).isEqualTo("CancelTask");
        assertThat(evaluations.get(1).at("/action/name").asString()).isEqualTo("GetTask");
    }

    private void listJobs(String selector, String continueToken, String body) {
        com.github.tomakehurst.wiremock.client.MappingBuilder request =
                WireMock.get(WireMock.urlPathEqualTo("/apis/batch/v1/namespaces/default/jobs"))
                        .withQueryParam("labelSelector", equalTo(selector));
        request =
                continueToken == null
                        ? request.withQueryParam("continue", WireMock.absent())
                        : request.withQueryParam("continue", equalTo(continueToken));
        mockKubernetes.givenThat(request.willReturn(okJson(body)));
    }

    private static V1Job named(V1Job job, String name, String owner) {
        job.getMetadata().setName(name);
        if (owner != null) {
            job.getMetadata().putAnnotationsItem("owner-id", owner);
        }
        return job;
    }

    private static String page(String continueToken, V1Job... jobs) {
        return new JSON()
                .getGson()
                .toJson(
                        new V1JobList()
                                .metadata(new V1ListMeta()._continue(continueToken))
                                .items(Arrays.asList(jobs)));
    }

    /** Case 12: all=true lists every task, without a creator selector; tokens carry the scope */
    @Test
    public void case12_listAll() throws Exception {
        userInfo(SUBGROUP);
        decide("ListTasks", "all", true);
        decide("ListTasks", "own", false);
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);
        listJobs(
                "job-type=taskmaster",
                null,
                page(
                        "k8s-next",
                        named(MockUtil.taskmaster(null, "bob"), "task-a", null),
                        named(MockUtil.taskmaster(null, ALICE), "task-b", null)));
        listJobs("job-type=taskmaster", "k8s-next", page(null));

        listTasks(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.next_page_token").value("all:k8s-next"));
        listTasks("all:k8s-next")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(0))
                .andExpect(jsonPath("$.next_page_token").doesNotExist());
        List<JsonNode> evaluations = evaluations();
        assertThat(evaluations).hasSize(4);
        assertThat(evaluations.get(0).get("resource"))
                .isEqualTo(MAPPER.readTree("{\"type\": \"tes_task_set\", \"id\": \"all\"}"));
        assertThat(evaluations.get(1).get("resource"))
                .isEqualTo(MAPPER.readTree("{\"type\": \"tes_task_set\", \"id\": \"own\"}"));
        assertThat(evaluations.get(0).at("/action/properties/view").asString())
                .isEqualTo("MINIMAL");
    }

    /** Case 13 and 13b: own tasks by the exact owner; a token of the other scope gives 400 */
    @Test
    public void case13_listOwn() throws Exception {
        userInfo(SUBGROUP);
        decide("ListTasks", "all", false);
        decide("ListTasks", "own", true);
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);
        listJobs(
                "job-type=taskmaster,creator-user-hash=" + CreatorLabels.hash(ALICE),
                null,
                page(
                        null,
                        named(MockUtil.taskmaster(null, ALICE), "task-a", ISS + "|" + ALICE),
                        named(MockUtil.taskmaster(null, ALICE), "task-b", "https://other|" + ALICE),
                        named(MockUtil.taskmaster(null, ALICE), "task-c", null)));

        listTasks(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.tasks[0].id").value("task-a"))
                .andExpect(jsonPath("$.tasks[1].id").value("task-c"));
        listTasks("all:k8s-next").andExpect(status().isBadRequest());
        listTasks("k8s-next").andExpect(status().isBadRequest());
    }

    /** Case 14: both sets denied: 403 */
    @Test
    public void case14_listDenied() throws Exception {
        userInfo(SUBGROUP);
        decide("ListTasks", null, false);

        listTasks(null).andExpect(status().isForbidden());
        assertThat(evaluations()).hasSize(2);
    }

    /** Case 7b: the client's ID and state are ignored */
    @Test
    public void case07b_serverAssignedId() throws Exception {
        userInfo(SUBGROUP);
        decide("CreateTask", "own", true);
        mockCreate();

        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(
                                        "{\"id\": \"task-123\", \"state\": \"COMPLETE\","
                                                + " \"executors\": [{\"image\": \"alpine\","
                                                + " \"command\": [\"true\"]}]}")
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-fe99716a"));
        mockKubernetes.verify(
                WireMock.postRequestedFor(
                                WireMock.urlEqualTo("/apis/batch/v1/namespaces/default/jobs"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.annotations['json-input']",
                                        matching("^((?!task-123|COMPLETE).)*$"))));
        assertThat(evaluations().get(0).at("/resource/properties/task").has("id")).isFalse();
    }
}
