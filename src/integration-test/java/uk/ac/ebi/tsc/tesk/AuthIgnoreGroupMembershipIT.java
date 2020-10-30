package uk.ac.ebi.tsc.tesk;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.kubernetes.client.ApiClient;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.TestUtils.getFileContentFromResources;

/**
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 * <p>
 * Integration testing of security (authentication and authorisation using OIDC and Elixir groups)
 * Kubernetes API and OIDC userInfo endpoint are WireMocked
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = {"classpath:application.properties"},
        properties = {"security.oauth2.resource.user-info-uri = http://localhost:8090",
                "spring.profiles.active=auth", "tesk.api.authorisation.ignoreGroupMembership=true"})
public class AuthIgnoreGroupMembershipIT {

    @Rule
    public WireMockRule mockElixir = new WireMockRule(8090);

    @Rule
    public WireMockRule mockKubernetes = new WireMockRule(wireMockConfig().port(9000).usingFilesUnderDirectory("src/integration-test/resources"));

    @Autowired
    private MockMvc mvc;

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    @Test
    public void authorizedUser_createTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(okJson("{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.post("/apis/batch/v1/namespaces/default/jobs")
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-fe99716a\"}}")));

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc.perform(post("/v1/tasks")
                .content(getFileContentFromResources(path))
                .header("Authorization", "Bearer BAR")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
    }


    @Test
    public void author_getTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(okJson("{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);


        this.mvc.perform(get("/v1/tasks/{id}", "task-123")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());

        this.mvc.perform(get("/v1/tasks/{id}?view=BASIC", "task-123")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
        this.mvc.perform(get("/v1/tasks/{id}?view=FULL", "task-123")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
    }

    @Test
    public void nonAuthor_getTask() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(okJson("{\"sub\" : \"124\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        MockUtil.mockGetTaskKubernetesResponses(this.mockKubernetes);

        this.mvc.perform(get("/v1/tasks/{id}", "task-123")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
        this.mvc.perform(get("/v1/tasks/{id}?view=BASIC", "task-123")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
        this.mvc.perform(get("/v1/tasks/{id}?view=FULL", "task-123")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void author_not_in_group_getList() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(okJson("{\"sub\" : \"123\"}")));

        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster" +
                        "%2Ccreator-user-id%3D123")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));

        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        this.mvc.perform(get("/v1/tasks")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
        this.mvc.perform(get("/v1/tasks?view=BASIC")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
        this.mvc.perform(get("/v1/tasks?view=FULL")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
    }


    @Test
    public void author_in_group_getList() throws Exception {

        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(okJson("{\"sub\" : \"123\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\",\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));

        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster%2Ccreator-user-id%3D123")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        this.mvc.perform(get("/v1/tasks")
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());

        verify(exactly(0), getRequestedFor(urlEqualTo("/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster" +
                "%2Ccreator-group-name%20in%20%28TEST%29%2Ccreator-user-id%3D123")));
    }

    @Test
    public void unauthenicated_createTask() throws Exception {

        String path = "fromTesToK8s_minimal/task.json";
        this.mvc.perform(post("/v1/tasks")
                .content(getFileContentFromResources(path))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    public void unauthenticated_getTask() throws Exception {
        this.mvc.perform(get("/v1/tasks/{id}", 123))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void unauthenticated_getList() throws Exception {

        this.mvc.perform(get("/v1/tasks")
                .header("Authorization", "different BAR"))
                .andExpect(status().isUnauthorized());
        this.mvc.perform(get("/v1/tasks?view=BASIC")
                .header("Different", "Bearer BAR"))
                .andExpect(status().isUnauthorized());
        this.mvc.perform(get("/v1/tasks?view=FULL"))
                .andExpect(status().isUnauthorized());

    }

}
