package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The transition setting tesk.api.authorisation.legacy-user-labels: tasks created before the hash
 * label (with only the old label {@code creator-user-id}) belong to the users whose ID gives that
 * label value in the old encoding; tasks with the hash label still need the exact ID (issue #119).
 * Kubernetes API and OIDC userInfo endpoint are WireMocked.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=auth",
            "tesk.api.authorisation.legacy-user-labels=true"
        })
public class LegacyUserLabelsIT {

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

    private void member(String sub) {
        mockElixir.givenThat(
                WireMock.get("/")
                        .willReturn(
                                okJson(
                                        "{\"sub\" : \""
                                                + sub
                                                + "\",  \"eduperson_entitlement\" : [\"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz\", \"urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:TEST#perun.elixir-czech.cz\"]}")));
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

    @Test
    public void legacyTask_creatorByOldEncoding() throws Exception {
        MockUtil.mockLegacyTaskKubernetesResponses(this.mockKubernetes, "TEST", "user_example.org");
        member("user@example.org");
        expectGetTask(200);
        member("someone@example.org");
        expectGetTask(403);
    }

    /** Tasks with the hash label still need the exact ID */
    @Test
    public void newTask_exactIdOnly() throws Exception {
        MockUtil.mockGroupTaskKubernetesResponses(this.mockKubernetes, "TEST", "user_example.org");
        member("user@example.org");
        expectGetTask(403);
        member("user_example.org");
        expectGetTask(200);
    }

    /**
     * Members list by group only, as Kubernetes can't select the creator by either label; TESK API
     * checks the creator of each task
     */
    @Test
    public void member_getList() throws Exception {
        member("123");
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dtaskmaster"
                                        + "%2Ccreator-group-name%20in%20%28TEST%29"
                                        + "&limit=256")
                        .willReturn(aResponse().withBodyFile("list/taskmasters.json")));
        MockUtil.mockListTaskKubernetesResponses(this.mockKubernetes);

        this.mvc
                .perform(get(TASK_URL).header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2));
    }
}
