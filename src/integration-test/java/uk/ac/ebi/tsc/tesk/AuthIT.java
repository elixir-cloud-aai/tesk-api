package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.TestUtils.getFileContentFromResources;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SERVICE_INFO_URL;
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
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * Integration testing of security (authentication and authorisation using OIDC and Elixir groups)
 * Kubernetes API and OIDC userInfo endpoint are WireMocked
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
            "spring.profiles.active=auth"
        })
public class AuthIT {

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

    @Test
    public void admin_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:ADMIN#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels['creator-group-name']", absent()))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-user-hash == 'a665a45920422f9d417e4867efdc4fb8')]"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    public void adminAndMember_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:ADMIN#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-group-name == 'TEST')]"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-user-hash == 'a665a45920422f9d417e4867efdc4fb8')]"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    public void adminChosenGroup_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:ADMIN#perun.elixir-czech.cz\"]}")));

        String path = "fromTesToK8s/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    public void authorizedUser_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-group-name == 'TEST')]"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-user-hash == 'a665a45920422f9d417e4867efdc4fb8')]"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    public void multiGroups_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:ABC#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-user-hash == 'a665a45920422f9d417e4867efdc4fb8')]"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    public void chosenGroup_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:ABC#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-user-hash == 'a665a45920422f9d417e4867efdc4fb8')]"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-group-name == 'ABC')]"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        mockKubernetes.givenThat(
                WireMock.get("/api/v1/namespaces/default/limitranges")
                        .willReturn(okJson("{\"metadata\":{\"name\":\"core-resource-limits\"}}")));

        String path = "fromTesToK8s/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    public void wrongChosenGroup_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:XYZ#perun.elixir-czech.cz\"]}")));

        String path = "fromTesToK8s/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    public void unauthenicated_createTask() throws Exception {

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void differentGroup_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\":\"123\",\"eduperson_entitlement\":[\"urn:geant:elixir-europe.org:group:elixir:different#perun.elixir-czech.cz\"]}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer BAR")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    public void noGroups_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(okJson("{\"sub\":\"123\",\"eduperson_entitlement\":[]}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    public void noGroupsScope_createTask() throws Exception {

        mockElixir.givenThat(WireMock.get("/").willReturn(okJson("{\"sub\":\"123\"}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    public void wrongGroupPrefix_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\":\"123\",\"eduperson_entitlement\":[\"GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\"]}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(getFileContentFromResources(path))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
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
    public void authorized_getTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(get(TASK_URL + "/{id}", "task-123").header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());

        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=BASIC", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=FULL", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
    }

    @Test
    public void authorized_cancelTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(
                        post(TASK_URL + "/{id}:cancel", "task-123")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Job with ID=task-123 has no pods in RUNNING status."));
    }

    @Test
    public void nonauthorized_cancelTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"124\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(
                        post(TASK_URL + "/{id}:cancel", "task-123")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void memberNonAuthor_getTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"124\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(get(TASK_URL + "/{id}", "task-123").header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=BASIC", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=FULL", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void adminNonAuthor_getTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"124\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST:ADMIN#perun.elixir-czech.cz\"]}")));

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(get(TASK_URL + "/{id}", "task-123").header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=BASIC", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=FULL", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
    }

    @Test
    public void authorNonMember_getTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\"]}")));

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(get(TASK_URL + "/{id}", "task-123").header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=BASIC", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=FULL", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void superAdmin_getTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"xyz\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:ADMIN#perun.elixir-czech.cz\"]}")));

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(get(TASK_URL + "/{id}", "task-123").header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=BASIC", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=FULL", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
    }

    @Test
    public void member_getList() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\",\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-group-name%20in%20%28TEST%29%2Ccreator-user-hash%3Da665a45920422f9d417e4867efdc4fb8")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        // only the own tasks (task-123 and task-125), also if Kubernetes returned others
        performListTask(2);
    }

    @Test
    public void superAdmin_getList() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:ADMIN#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        performListTask(4);
    }

    @Test
    public void groupAdmin_getList() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST:ADMIN#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-group-name%20in%20%28TEST%29")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        // the tasks of the group TEST, and the own task-125 of the group ABC, which Kubernetes
        // wouldn't return for this selector
        performListTask(3);
    }

    @Test
    public void mixed_getList() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:ABC:ADMIN#perun.elixir-czech.cz\",\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-group-name%20in%20%28TEST%2CABC%29")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        performListTask(3);
    }

    @Test
    public void unauthenticated_getList() throws Exception {

        this.mvc
                .perform(get(TASK_URL).header("Authorization", "different BAR"))
                .andExpect(status().isUnauthorized());
        this.mvc
                .perform(get(TASK_URL + "?view=BASIC").header("Different", "Bearer BAR"))
                .andExpect(status().isUnauthorized());
        this.mvc.perform(get(TASK_URL + "?view=FULL")).andExpect(status().isUnauthorized());
    }

    @Test
    public void nonmember_getList() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"sth\",\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP#perun.elixir-czech.cz\"]}")));

        this.mvc
                .perform(get(TASK_URL).header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
        this.mvc
                .perform(get(TASK_URL + "?view=BASIC").header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
        this.mvc
                .perform(get(TASK_URL + "?view=FULL").header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
    }

    /**
     * Without tesk.api.authorisation.claim.enabled (the default), a claim doesn't authorise a user
     * without groups (issue #78)
     */
    @Test
    public void claimWithoutGroups_settingOff() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \"123\",  \"eduperson_assurance\" : [\"https://refeds.org/assurance/IAP/medium\"]}")));

        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(
                                        getFileContentFromResources(
                                                "fromTesToK8s_minimal/task.json"))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        this.mvc
                .perform(get(TASK_URL).header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());

        // nor get or cancel the own task without a group
        MockUtil.mockUngroupedTaskKubernetesResponses(this.mockKubernetes, "123");
        this.mvc
                .perform(get(TASK_URL + "/{id}", "task-123").header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
        this.mvc
                .perform(
                        post(TASK_URL + "/{id}:cancel", "task-123")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void anybody_can_see_serviceInfo() throws Exception {
        this.mvc.perform(get(SERVICE_INFO_URL)).andExpect(status().isOk());
    }

    @Test
    public void public_serviceInfo_withValidToken_isOk() throws Exception {
        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson("{\"sub\" : \"123\",  \"eduperson_entitlement\" : []}")));

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

    private void expectGetTask(int status) throws Exception {
        for (String view : new String[] {"MINIMAL", "BASIC", "FULL"}) {
            this.mvc
                    .perform(
                            get(TASK_URL + "/{id}?view=" + view, "task-123")
                                    .header("Authorization", "Bearer BAR"))
                    .andExpect(status().is(status));
        }
    }

    /** IDs that the old label encoding merged are told apart (issue #119) */
    @Test
    public void similarIds_getTask() throws Exception {
        MockUtil.mockGroupTaskKubernetesResponses(this.mockKubernetes, "TEST", "a@b");
        userInfo("a_b", "GA4GH:GA4GH-CAP:EBI", "GA4GH:GA4GH-CAP:EBI:TEST");
        expectGetTask(403);
        userInfo("a@b", "GA4GH:GA4GH-CAP:EBI", "GA4GH:GA4GH-CAP:EBI:TEST");
        expectGetTask(200);
    }

    /** IDs that aren't valid label values, e.g. longer than 63 characters, work (issue #119) */
    @Test
    public void longId_createAndGetTask() throws Exception {
        StringBuilder id = new StringBuilder("user|");
        for (int i = 0; i < 70; i++) {
            id.append("x");
        }
        userInfo(id.toString(), "GA4GH:GA4GH-CAP:EBI", "GA4GH:GA4GH-CAP:EBI:TEST");
        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.labels[?(@.creator-user-hash == '"
                                                + CreatorLabels.hash(id.toString())
                                                + "')]"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.metadata.annotations[?(@.creator-user-id == '"
                                                + id
                                                + "')]"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));
        mockKubernetes.givenThat(
                WireMock.post(WireMock.urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));
        this.mvc
                .perform(
                        post(TASK_URL)
                                .content(
                                        getFileContentFromResources(
                                                "fromTesToK8s_minimal/task.json"))
                                .header("Authorization", "Bearer BAR")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        MockUtil.mockGroupTaskKubernetesResponses(this.mockKubernetes, "TEST", id.toString());
        expectGetTask(200);
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=BASIC", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(jsonPath("$.logs[0].metadata.USER_ID").value(id.toString()));
    }

    /**
     * Tasks with only the old label belong to no user while
     * tesk.api.authorisation.legacy-user-labels is off (the default); group admins and super admins
     * still get them (issue #119)
     */
    @Test
    public void legacyLabel_getTask() throws Exception {
        MockUtil.mockLegacyTaskKubernetesResponses(this.mockKubernetes, "TEST", "123");
        userInfo("123", "GA4GH:GA4GH-CAP:EBI", "GA4GH:GA4GH-CAP:EBI:TEST");
        expectGetTask(403);
        userInfo("124", "GA4GH:GA4GH-CAP:EBI:TEST:ADMIN");
        expectGetTask(200);
        userInfo("124", "GA4GH:GA4GH-CAP:EBI:ADMIN");
        expectGetTask(200);
        this.mvc
                .perform(
                        get(TASK_URL + "/{id}?view=BASIC", "task-123")
                                .header("Authorization", "Bearer BAR"))
                .andExpect(jsonPath("$.logs[0].metadata.USER_ID").value("123"));
    }

    private void performListTask(int expectedLength) throws Exception {
        this.mvc
                .perform(get(TASK_URL).header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(expectedLength));
        this.mvc
                .perform(get(TASK_URL + "?view=BASIC").header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(expectedLength));
        this.mvc
                .perform(get(TASK_URL + "?view=FULL").header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(expectedLength));
    }
}
