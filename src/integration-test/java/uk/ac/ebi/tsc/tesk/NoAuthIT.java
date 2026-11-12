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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.Config;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Integration testing of switched off security Kubernetes API is WireMocked
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=noauth"
        })
public class NoAuthIT {

    @Autowired private MockMvc mvc;

    @Autowired private ObjectMapper objectMapper;

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

    @Test
    public void createTask() throws Exception {

        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels['creator-group-name']", absent()))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-user-hash == '68385f9726ad05c8e551a8d5be5cd821')]"))
                        .willReturn(
                                okJson(
                                        "{\"metadata\":{\"name\":\"task-fe99716a\",\"uid\":\"uid-1\"}}")));

        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // the ConfigMap is checked in a dry run (with a placeholder owner), then created, owned by
        // the created job
        mockKubernetes.verify(
                1,
                WireMock.postRequestedFor(
                                WireMock.urlEqualTo(
                                        "/api/v1/namespaces/default/configmaps?dryRun=All"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.ownerReferences[?(@.kind == 'Job')]")));
        mockKubernetes.verify(
                1,
                WireMock.postRequestedFor(
                                WireMock.urlEqualTo("/api/v1/namespaces/default/configmaps"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.ownerReferences[?(@.kind == 'Job'"
                                                + " && @.name == 'task-fe99716a'"
                                                + " && @.uid == 'uid-1')]")));
    }

    @Test
    public void createTaskWithPrivateExecutorImage() throws Exception {
        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .willReturn(
                                okJson(
                                        "{\"metadata\":{\"name\":\"task-fe99716a\",\"uid\":\"uid-1\"}}")));
        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/secrets"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a-ex-01\"}}")));
        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        String task =
                "{\"executors\":["
                        + "{\"image\":\"ubuntu:22.04\",\"command\":[\"true\"]},"
                        + "{\"image\":\"registry.example.org/project/tool:1.0\","
                        + "\"command\":[\"true\"],"
                        + "\"docker_registry\":\"registry.example.org\","
                        + "\"docker_login\":\"robot\",\"docker_pass\":\"private-token\"}]}";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(task)
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-fe99716a"));

        List<LoggedRequest> jobRequests =
                mockKubernetes.findAll(
                        postRequestedFor(urlPathEqualTo("/apis/batch/v1/namespaces/default/jobs")));
        assertThat(jobRequests).hasSize(1);
        String generatedName =
                objectMapper
                        .readTree(jobRequests.get(0).getBodyAsString())
                        .path("metadata")
                        .path("name")
                        .asText();
        assertThat(jobRequests.get(0).getBodyAsString())
                .doesNotContain("robot", "private-token", "docker_login", "docker_pass");

        List<LoggedRequest> secretRequests =
                mockKubernetes.findAll(
                        postRequestedFor(urlPathEqualTo("/api/v1/namespaces/default/secrets")));
        assertThat(secretRequests).hasSize(2);
        for (LoggedRequest request : secretRequests) {
            JsonNode secret = objectMapper.readTree(request.getBodyAsString());
            assertThat(secret.path("metadata").path("name").asText())
                    .isEqualTo(generatedName + "-ex-01");
            assertThat(secret.path("type").asText()).isEqualTo("kubernetes.io/dockerconfigjson");
            String encoded = secret.path("data").path(".dockerconfigjson").asText();
            JsonNode config = objectMapper.readTree(Base64.getDecoder().decode(encoded));
            String auth = config.path("auths").path("registry.example.org").path("auth").asText();
            assertThat(new String(Base64.getDecoder().decode(auth), StandardCharsets.UTF_8))
                    .isEqualTo("robot:private-token");
            String uid =
                    secret.path("metadata").path("ownerReferences").get(0).path("uid").asText();
            if (request.getUrl().contains("dryRun=All")) {
                assertThat(uid).isNotEqualTo("uid-1");
            } else {
                assertThat(uid).isEqualTo("uid-1");
            }
        }

        JsonNode definitions =
                objectMapper
                        .readTree(
                                this.mvc
                                        .perform(get("/v2/api-docs"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("definitions");
        JsonNode requestProperties = definitions.path("TesExecutorReq").path("properties");
        JsonNode responseProperties = definitions.path("TesExecutorRes").path("properties");
        assertThat(requestProperties.has("docker_registry")).isTrue();
        assertThat(requestProperties.has("docker_login")).isTrue();
        assertThat(requestProperties.has("docker_pass")).isTrue();
        assertThat(responseProperties.has("docker_login")).isFalse();
        assertThat(responseProperties.has("docker_pass")).isFalse();
    }

    @Test
    public void getTask() throws Exception {

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc.perform(get(TASK_URL + "/{id}", "task-123")).andExpect(status().isOk());
        // logs[].outputs is required by TES: an empty array, as TESK doesn't report output files
        this.mvc
                .perform(get(TASK_URL + "/{id}?view=BASIC", "task-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].outputs").isArray())
                .andExpect(jsonPath("$.logs[0].outputs").isEmpty());
        this.mvc
                .perform(get(TASK_URL + "/{id}?view=FULL", "task-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].outputs").isArray())
                .andExpect(jsonPath("$.logs[0].outputs").isEmpty());
    }

    @Test
    public void cancelTask() throws Exception {

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(
                        post(TASK_URL + "/{id}:cancel", "task-123")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Job with ID=task-123 has no pods in RUNNING status."));
    }

    @Test
    public void getList() throws Exception {

        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(get(TASK_URL).header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(4));
        this.mvc
                .perform(get(TASK_URL + "?view=BASIC").header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(4))
                .andExpect(jsonPath("$.tasks[0].logs[0].outputs").isArray())
                .andExpect(jsonPath("$.tasks[0].logs[0].outputs").isEmpty());
        this.mvc
                .perform(get(TASK_URL + "?view=FULL").header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(4))
                .andExpect(jsonPath("$.tasks[0].logs[0].outputs").isArray())
                .andExpect(jsonPath("$.tasks[0].logs[0].outputs").isEmpty());
        // only task-124 has a name
        this.mvc
                .perform(
                        get(TASK_URL + "?name_prefix=align&view=BASIC")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].id").value("task-124"));
        this.mvc
                .perform(get(TASK_URL + "?name_prefix=call").header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(0));
    }

    @Test
    public void serviceInfo() throws Exception {
        this.mvc.perform(get(SERVICE_INFO_URL)).andExpect(status().isOk());
        // .andExpect(jsonPath("$.name").value("TESK in EBI"))
        // .andExpect(jsonPath("$.doc").value("https://github.com/EMBL-EBI-TSI/TESK"));
    }

    /** A running taskmaster job of a task with these tags, stored with the job. */
    private static V1Job taggedTaskmaster(String id, String tagsJson) {
        return new V1Job()
                .metadata(
                        new V1ObjectMeta()
                                .name(id)
                                .putLabelsItem("job-type", "taskmaster")
                                .putAnnotationsItem(
                                        "json-input",
                                        "{\"executors\":[],\"tags\":" + tagsJson + "}"))
                .status(new V1JobStatus().active(1));
    }

    /** Lists tasks by state and tags, with Kubernetes mocked (issue #143). */
    @Test
    public void getListFilteredByStateAndTags() throws Exception {
        V1Job cancelled = taggedTaskmaster("task-202", "{\"WORKFLOW_ID\":\"cwl-01234\"}");
        cancelled.getMetadata().putLabelsItem("task-status", "Cancelled");
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster")
                        .willReturn(
                                okJson(
                                        MockUtil.jobList(
                                                taggedTaskmaster(
                                                        "task-201",
                                                        "{\"WORKFLOW_ID\":\"cwl-01234\",\"a,b\":\"c\"}"),
                                                cancelled,
                                                taggedTaskmaster(
                                                        "task-203",
                                                        "{\"WORKFLOW_ID\":\"other\"}")))));
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dexecutor")
                        .willReturn(okJson("{\"items\": []}")));
        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs?labelSelector=%21job-type")
                        .willReturn(okJson("{\"items\": []}")));
        mockKubernetes.givenThat(
                WireMock.get("/api/v1/namespaces/default/pods?labelSelector=job-name")
                        .willReturn(okJson("{\"items\": []}")));

        this.mvc
                .perform(get(TASK_URL + "?tag_key=WORKFLOW_ID&tag_value=cwl-01234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.tasks[0].id").value("task-201"))
                .andExpect(jsonPath("$.tasks[1].id").value("task-202"));
        this.mvc
                .perform(get(TASK_URL + "?tag_key=WORKFLOW_ID&state=CANCELED&view=BASIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].id").value("task-202"))
                .andExpect(jsonPath("$.tasks[0].tags.WORKFLOW_ID").value("cwl-01234"));
        this.mvc
                .perform(get(TASK_URL).param("tag_key", "a,b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].id").value("task-201"));
        this.mvc
                .perform(get(TASK_URL + "?state=INITIALIZING&view=FULL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2));
        this.mvc
                .perform(get(TASK_URL + "?state=PREEMPTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(0));
        this.mvc.perform(get(TASK_URL + "?state=preempted")).andExpect(status().isBadRequest());
        this.mvc
                .perform(get(TASK_URL + "?tag_key=a&tag_value=b&tag_value=c"))
                .andExpect(status().isBadRequest());
    }

    /** The API description has the filters of TES 1.1 (issue #143). */
    @Test
    public void apiDescriptionHasListFilters() throws Exception {
        JsonNode parameters =
                objectMapper
                        .readTree(
                                this.mvc
                                        .perform(get("/v2/api-docs"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("paths")
                        .path("/ga4gh/tes/v1/tasks")
                        .path("get")
                        .path("parameters");
        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode parameter : parameters) {
            byName.put(parameter.path("name").asText(), parameter);
        }
        JsonNode state = byName.get("state");
        assertThat(state).isNotNull();
        assertThat(state.path("in").asText()).isEqualTo("query");
        List<String> states = new ArrayList<>();
        state.path("enum").forEach(value -> states.add(value.asText()));
        assertThat(states).contains("CANCELING", "PREEMPTED", "RUNNING");
        for (String name : new String[] {"tag_key", "tag_value"}) {
            JsonNode tagParameter = byName.get(name);
            assertThat(tagParameter).as(name).isNotNull();
            assertThat(tagParameter.path("in").asText()).isEqualTo("query");
            assertThat(tagParameter.path("type").asText()).isEqualTo("array");
            assertThat(tagParameter.path("collectionFormat").asText()).isEqualTo("multi");
        }
    }
}
