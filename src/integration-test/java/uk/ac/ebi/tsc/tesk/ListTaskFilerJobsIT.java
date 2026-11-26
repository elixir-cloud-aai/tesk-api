package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.google.gson.Gson;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Listing tasks reads only the filer jobs of the page's tasks, by their label {@code
 * taskmaster-name} (tesk-core#147), and falls back to the unlabelled filer jobs of older
 * taskmasters (issue #177). The Kubernetes API is WireMocked and fails on unstubbed requests; its
 * job lists are stubbed per label selector with the jobs that Kubernetes would select by it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=noauth"
        })
public class ListTaskFilerJobsIT {

    private static final Gson GSON = new JSON().getGson();

    private static final String JOBS = "/apis/batch/v1/namespaces/default/jobs";

    private static final String LABELLED = "!job-type,taskmaster-name in (task-new,task-old)";

    private static final String UNLABELLED = "!job-type,!taskmaster-name";

    @Autowired private MockMvc mvc;

    @RegisterExtension
    public WireMockExtension mockKubernetes =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(9000))
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

    /**
     * Both tasks run and their outputs filers failed: the new taskmaster's filer job is labelled,
     * the old one's isn't. Both tasks are SYSTEM_ERROR, so both filer jobs were found; the
     * unlabelled filer job of a task that isn't listed is ignored.
     */
    @Test
    public void findsLabelledAndUnlabelledFilerJobs() throws Exception {
        mockTaskmasters();
        mockFilerJobs(LABELLED, outputsFiler("task-new", true));
        mockFilerJobs(UNLABELLED, outputsFiler("task-old", false), outputsFiler("task-9", false));

        this.mvc
                .perform(get(TASK_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.tasks[0].id").value("task-new"))
                .andExpect(jsonPath("$.tasks[0].state").value("SYSTEM_ERROR"))
                .andExpect(jsonPath("$.tasks[1].id").value("task-old"))
                .andExpect(jsonPath("$.tasks[1].state").value("SYSTEM_ERROR"));
    }

    /**
     * If every task of the page has a labelled filer job, e.g. only the inputs filer's so far, the
     * unlabelled filer jobs aren't listed.
     */
    @Test
    public void skipsUnlabelledFilerJobsIfAllTasksHaveLabelledOnes() throws Exception {
        mockTaskmasters();
        mockFilerJobs(LABELLED, outputsFiler("task-new", true), inputsFiler("task-old"));

        this.mvc
                .perform(get(TASK_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].state").value("SYSTEM_ERROR"))
                .andExpect(jsonPath("$.tasks[1].state").value("INITIALIZING"));
        mockKubernetes.verify(
                0,
                getRequestedFor(urlPathEqualTo(JOBS))
                        .withQueryParam("labelSelector", equalTo(UNLABELLED)));
    }

    /** Two running taskmasters, no executors and no pods */
    private void mockTaskmasters() {
        mockKubernetes.givenThat(
                WireMock.get(urlPathEqualTo(JOBS))
                        .withQueryParam(
                                "labelSelector",
                                equalTo(MockUtil.ownTaskmastersSelector(MockUtil.NOAUTH_USER)))
                        .willReturn(
                                okJson(
                                        MockUtil.jobList(
                                                MockUtil.createdBy(
                                                        job("task-new", "taskmaster")
                                                                .status(
                                                                        new V1JobStatus()
                                                                                .active(1)),
                                                        MockUtil.NOAUTH_USER),
                                                MockUtil.createdBy(
                                                        job("task-old", "taskmaster")
                                                                .status(
                                                                        new V1JobStatus()
                                                                                .active(1)),
                                                        MockUtil.NOAUTH_USER)))));
        mockKubernetes.givenThat(
                WireMock.get(urlPathEqualTo(JOBS))
                        .withQueryParam("labelSelector", equalTo("job-type=executor"))
                        .willReturn(okJson(MockUtil.jobList())));
        mockKubernetes.givenThat(
                WireMock.get(urlPathEqualTo("/api/v1/namespaces/default/pods"))
                        .willReturn(okJson(GSON.toJson(new V1PodList()))));
    }

    private void mockFilerJobs(String labelSelector, V1Job... jobs) {
        mockKubernetes.givenThat(
                WireMock.get(urlPathEqualTo(JOBS))
                        .withQueryParam("labelSelector", equalTo(labelSelector))
                        .willReturn(okJson(MockUtil.jobList(jobs))));
    }

    /** A failed outputs filer's job, labelled as by a taskmaster with tesk-core#147, or not */
    private static V1Job outputsFiler(String task, boolean labelled) {
        V1Job job =
                job(task + "-outputs-filer", null)
                        .status(
                                new V1JobStatus()
                                        .failed(1)
                                        .addConditionsItem(
                                                new V1JobCondition()
                                                        .type("Failed")
                                                        .status("True")));
        if (labelled) {
            job.getMetadata().putLabelsItem("taskmaster-name", task);
        }
        return job;
    }

    /** A running inputs filer's job, labelled as by a taskmaster with tesk-core#147 */
    private static V1Job inputsFiler(String task) {
        V1Job job = job(task + "-inputs-filer", null).status(new V1JobStatus().active(1));
        job.getMetadata().putLabelsItem("taskmaster-name", task);
        return job;
    }

    private static V1Job job(String name, String type) {
        V1ObjectMeta metadata = new V1ObjectMeta().name(name);
        if (type != null) {
            metadata.putLabelsItem("job-type", type);
        }
        return new V1Job()
                .metadata(metadata)
                .spec(
                        new V1JobSpec()
                                .selector(
                                        new V1LabelSelector()
                                                .putMatchLabelsItem("controller-uid", name)));
    }
}
