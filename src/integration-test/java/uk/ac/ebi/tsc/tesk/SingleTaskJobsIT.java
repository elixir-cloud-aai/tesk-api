package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Reading a single task whose filer jobs carry the label {@code taskmaster-name}, as taskmasters
 * with tesk-core#147 label them: only the executors' jobs count as executors, and only the job
 * {@code <task>-outputs-filer} as the outputs filer (issue #182). The Kubernetes API is WireMocked;
 * its job lists are stubbed per label selector with the jobs that Kubernetes would select by it.
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
public class SingleTaskJobsIT {

    private static final Gson GSON = new JSON().getGson();

    private static final String TASK = "task-123";

    private static final String JOBS = "/apis/batch/v1/namespaces/default/jobs";

    @Autowired private MockMvc mvc;

    @Rule public WireMockRule mockKubernetes = new WireMockRule(wireMockConfig().port(9000));

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    /**
     * After an ignored executor failure whose pods didn't end in time, the taskmaster stops before
     * the outputs filer: the task is SYSTEM_ERROR, although its inputs filer succeeded. Before the
     * fix, the inputs filer stood in as the outputs filer, and the task was COMPLETE.
     */
    @Test
    public void ignoredFailureWithoutOutputsFilerIsSystemError() throws Exception {
        V1Job executor = executor().status(failed());
        executor.getMetadata().putAnnotationsItem("ignore-error", "true");
        mockTask(
                taskmaster(new V1JobStatus().succeeded(1)),
                executor,
                inputsFiler(new V1JobStatus().succeeded(1)));

        this.mvc
                .perform(get(TASK_URL + "/{id}", TASK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SYSTEM_ERROR"));
    }

    /**
     * A failed inputs filer isn't taken as a failed outputs filer: while the taskmaster still runs
     * (e.g. cleans up), the task isn't SYSTEM_ERROR yet.
     */
    @Test
    public void failedInputsFilerIsNotTheOutputsFiler() throws Exception {
        mockTask(taskmaster(new V1JobStatus().active(1)), null, inputsFiler(failed()));

        this.mvc
                .perform(get(TASK_URL + "/{id}", TASK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INITIALIZING"));
    }

    /**
     * Stubs the Kubernetes API for the task's taskmaster, executor (or none) and inputs filer, with
     * no outputs filer and no pods. Lists of jobs by the label {@code taskmaster-name} give the
     * executor and the inputs filer; with {@code job-type=executor}, only the executor.
     */
    private void mockTask(V1Job taskmaster, V1Job executor, V1Job inputsFiler) {
        V1Job[] executors = executor == null ? new V1Job[0] : new V1Job[] {executor};
        V1Job[] labelled =
                executor == null ? new V1Job[] {inputsFiler} : new V1Job[] {executor, inputsFiler};
        mockKubernetes.givenThat(
                WireMock.get(urlPathEqualTo(JOBS))
                        .withQueryParam("labelSelector", equalTo("taskmaster-name=" + TASK))
                        .willReturn(okJson(MockUtil.jobList(labelled))));
        mockKubernetes.givenThat(
                WireMock.get(urlPathEqualTo(JOBS))
                        .withQueryParam(
                                "labelSelector",
                                equalTo("taskmaster-name=" + TASK + ",job-type=executor"))
                        .willReturn(okJson(MockUtil.jobList(executors))));
        mockKubernetes.givenThat(
                WireMock.get(JOBS + "/" + TASK).willReturn(okJson(GSON.toJson(taskmaster))));
        mockKubernetes.givenThat(
                WireMock.get(JOBS + "/" + TASK + "-outputs-filer").willReturn(notFound()));
        mockKubernetes.givenThat(
                WireMock.get(urlPathEqualTo("/api/v1/namespaces/default/pods"))
                        .willReturn(okJson(GSON.toJson(new V1PodList()))));
    }

    private static V1Job taskmaster(V1JobStatus status) {
        return job(TASK, "taskmaster").status(status);
    }

    private static V1Job executor() {
        V1Job job = job(TASK + "-ex-00", "executor");
        job.getMetadata().putLabelsItem("taskmaster-name", TASK);
        return job;
    }

    /** The inputs filer's job, labelled as by a taskmaster with tesk-core#147 */
    private static V1Job inputsFiler(V1JobStatus status) {
        V1Job job = job(TASK + "-inputs-filer", null).status(status);
        job.getMetadata().putLabelsItem("taskmaster-name", TASK);
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

    private static V1JobStatus failed() {
        return new V1JobStatus()
                .failed(1)
                .addConditionsItem(new V1JobCondition().type("Failed").status("True"));
    }
}
