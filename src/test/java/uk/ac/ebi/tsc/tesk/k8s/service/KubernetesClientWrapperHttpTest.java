package uk.ac.ebi.tsc.tesk.k8s.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.ClientBuilder;
import java.util.Collections;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.config.KubernetesClientConfig;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;

/**
 * The requests that the wrapper sends to Kubernetes through a real client: paths, query parameters,
 * bodies and the handling of errors (the other tests mock the client)
 */
public class KubernetesClientWrapperHttpTest {

    private static final String JOBS = "/apis/batch/v1/namespaces/tesk/jobs";

    private static final String PODS = "/api/v1/namespaces/tesk/pods";

    @RegisterExtension
    public WireMockExtension kubernetes =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private KubernetesClientWrapper wrapper;

    @BeforeEach
    public void setUp() {
        KubernetesClientConfig config = new KubernetesClientConfig();
        ApiClient client = new ClientBuilder().setBasePath(kubernetes.baseUrl()).build();
        ApiClient patchClient = config.patchApiClient(client);
        wrapper =
                new KubernetesClientWrapper(
                        new BatchV1Api(client),
                        config.patchBatchApi(patchClient),
                        new CoreV1Api(client),
                        config.patchCoreApi(patchClient),
                        "tesk");
    }

    /** A page of taskmasters' jobs: the selector, the page token and the page size */
    @Test
    public void listTaskmasterJobs() {
        kubernetes.givenThat(get(urlPathEqualTo(JOBS)).willReturn(okJson("{\"items\":[]}")));
        wrapper.listAllTaskmasterJobsForUser("token-1", 10, User.builder("123").build());
        kubernetes.verify(
                getRequestedFor(urlPathEqualTo(JOBS))
                        .withQueryParam("labelSelector", equalTo("job-type=taskmaster"))
                        .withQueryParam("continue", equalTo("token-1"))
                        .withQueryParam("limit", equalTo("10")));
    }

    /** Without a page token or size, the request has neither */
    @Test
    public void listExecutorJobs() {
        kubernetes.givenThat(get(urlPathEqualTo(JOBS)).willReturn(okJson("{\"items\":[]}")));
        wrapper.listAllTaskExecutorJobs();
        kubernetes.verify(
                getRequestedFor(urlPathEqualTo(JOBS))
                        .withQueryParam("labelSelector", equalTo("job-type=executor"))
                        .withQueryParam("continue", absent())
                        .withQueryParam("limit", absent()));
    }

    /** A ConfigMap is checked in a dry run, and created without one */
    @Test
    public void configMaps() {
        kubernetes.givenThat(
                post(urlPathEqualTo("/api/v1/namespaces/tesk/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"task-1\"}}")));
        V1ConfigMap map = new V1ConfigMap().metadata(new V1ObjectMeta().name("task-1"));
        wrapper.checkConfigMap(map);
        wrapper.createConfigMap(map);
        kubernetes.verify(
                1,
                postRequestedFor(urlPathEqualTo("/api/v1/namespaces/tesk/configmaps"))
                        .withQueryParam("dryRun", equalTo("All")));
        kubernetes.verify(
                1,
                postRequestedFor(urlPathEqualTo("/api/v1/namespaces/tesk/configmaps"))
                        .withQueryParam("dryRun", absent()));
    }

    /** A job is deleted with its pods, in the background */
    @Test
    public void deleteJob() {
        kubernetes.givenThat(delete(urlPathEqualTo(JOBS + "/task-1")).willReturn(okJson("{}")));
        wrapper.deleteJob("task-1");
        kubernetes.verify(
                deleteRequestedFor(urlPathEqualTo(JOBS + "/task-1"))
                        .withQueryParam("propagationPolicy", equalTo("Background")));
    }

    /** Errors of Kubernetes become KubernetesExceptions with their status code */
    @Test
    public void error() {
        kubernetes.givenThat(
                delete(urlPathEqualTo(JOBS + "/task-1"))
                        .willReturn(aResponse().withStatus(403).withBody("{}")));
        assertThatThrownBy(() -> wrapper.deleteJob("task-1"))
                .isInstanceOfSatisfying(
                        KubernetesException.class,
                        e -> assertThat(e.getApiException().getCode()).isEqualTo(403));
    }

    /** A job that doesn't exist (404) is a task that doesn't exist */
    @Test
    public void readMissingJob() {
        kubernetes.givenThat(
                get(urlPathEqualTo(JOBS + "/task-1"))
                        .willReturn(aResponse().withStatus(404).withBody("{}")));
        assertThrows(TaskNotFoundException.class, () -> wrapper.readTaskmasterJob("task-1"));
    }

    /** The minimum memory of the namespace's LimitRanges, in GB */
    @Test
    public void minimumRamGb() {
        kubernetes.givenThat(
                get(urlPathEqualTo("/api/v1/namespaces/tesk/limitranges"))
                        .willReturn(
                                okJson(
                                        "{\"items\":[{\"spec\":{\"limits\":[{\"type\":\"Container\","
                                                + "\"min\":{\"memory\":\"2Gi\"}}]}}]}")));
        assertThat(wrapper.minimumRamGb()).isEqualTo(2.0);
        kubernetes.verify(
                getRequestedFor(urlPathEqualTo("/api/v1/namespaces/tesk/limitranges"))
                        .withQueryParam("labelSelector", absent())
                        .withQueryParam("continue", absent())
                        .withQueryParam("limit", absent()));
    }

    /** A pod's log as Kubernetes sends it (plain text), or null if Kubernetes refuses it */
    @Test
    public void readPodLog() {
        kubernetes.givenThat(
                get(urlPathEqualTo(PODS + "/pod-1/log"))
                        .willReturn(ok("hello\n").withHeader("Content-Type", "text/plain")));
        kubernetes.givenThat(
                get(urlPathEqualTo(PODS + "/pod-2/log"))
                        .willReturn(aResponse().withStatus(404).withBody("{}")));
        assertThat(wrapper.readPodLog("pod-1")).isEqualTo("hello\n");
        assertThat(wrapper.readPodLog("pod-2")).isNull();
    }

    /** Jobs and pods are labelled as cancelled with merge patches */
    @Test
    public void labelAsCancelled() {
        kubernetes.givenThat(patch(urlPathEqualTo(JOBS + "/task-1")).willReturn(okJson("{}")));
        kubernetes.givenThat(patch(urlPathEqualTo(PODS + "/pod-1")).willReturn(okJson("{}")));
        wrapper.labelJobAsCancelled("task-1");
        wrapper.labelPodAsCancelled("pod-1");
        String body = "{\"metadata\":{\"labels\":{\"task-status\":\"Cancelled\"}}}";
        kubernetes.verify(
                patchRequestedFor(urlPathEqualTo(JOBS + "/task-1"))
                        .withHeader("Content-Type", containing("application/merge-patch+json"))
                        .withRequestBody(equalToJson(body)));
        kubernetes.verify(
                patchRequestedFor(urlPathEqualTo(PODS + "/pod-1"))
                        .withHeader("Content-Type", containing("application/merge-patch+json"))
                        .withRequestBody(equalToJson(body)));
    }

    /** A job whose taskmaster doesn't run yet is labelled and ended in one patch */
    @Test
    public void cancelJobBeforeStart() {
        kubernetes.givenThat(patch(urlPathEqualTo(JOBS + "/task-1")).willReturn(okJson("{}")));
        wrapper.cancelJobBeforeStart("task-1");
        kubernetes.verify(
                patchRequestedFor(urlPathEqualTo(JOBS + "/task-1"))
                        .withRequestBody(
                                equalToJson(
                                        "{\"metadata\":{\"labels\":{\"task-status\":\"Cancelled\"}},"
                                                + "\"spec\":{\"parallelism\":0,"
                                                + "\"activeDeadlineSeconds\":1}}")));
    }

    /**
     * The highest minimum memory of all LimitRanges, in GB, skipping limits without a minimum or
     * without a minimum of memory
     */
    @Test
    public void minimumRamGb_highestOfAll() {
        kubernetes.givenThat(
                get(urlPathEqualTo("/api/v1/namespaces/tesk/limitranges"))
                        .willReturn(
                                okJson(
                                        "{\"items\":[{\"spec\":{\"limits\":["
                                                + "{\"type\":\"Pod\",\"min\":null},"
                                                + "{\"type\":\"Container\",\"min\":{\"cpu\":\"1\"}},"
                                                + "{\"type\":\"Container\",\"min\":{\"memory\":\"3Gi\"}}]}},"
                                                + "{\"spec\":{\"limits\":[{\"type\":\"Pod\","
                                                + "\"min\":{\"memory\":\"1Gi\"}}]}}]}")));
        assertThat(wrapper.minimumRamGb()).isEqualTo(3.0);
    }

    /** Without the LimitRanges, there is no minimum: tasks are created with the memory they ask */
    @Test
    public void minimumRamGb_zeroIfKubernetesRefuses() {
        kubernetes.givenThat(
                get(urlPathEqualTo("/api/v1/namespaces/tesk/limitranges"))
                        .willReturn(aResponse().withStatus(403).withBody("{}")));
        assertThat(wrapper.minimumRamGb()).isEqualTo(0.0);
    }

    /**
     * A job without labels (also explicitly null) has no group: only its creator could see it, and
     * this user isn't
     */
    @Test
    public void listTaskmasterJobs_jobWithoutLabels() {
        kubernetes.givenThat(
                get(urlPathEqualTo(JOBS))
                        .willReturn(
                                okJson(
                                        "{\"items\":[{\"metadata\":{\"name\":\"task-1\"}},"
                                                + "{\"metadata\":{\"name\":\"task-2\",\"labels\":null}}]}")));
        User member =
                User.builder("123").teskMemberedGroups(Collections.singleton("group-1")).build();
        assertThat(wrapper.listAllTaskmasterJobsForUser(null, null, member).getItems()).isEmpty();
    }

    /** Every other call turns the errors of Kubernetes into KubernetesExceptions */
    @Test
    public void errorsOfAllCalls() {
        kubernetes.givenThat(any(anyUrl()).willReturn(aResponse().withStatus(500).withBody("{}")));
        V1Job job =
                new V1Job()
                        .spec(
                                new V1JobSpec()
                                        .selector(
                                                new V1LabelSelector()
                                                        .putMatchLabelsItem("job-name", "task-1")));
        assertStatus500(() -> wrapper.createJob(job));
        assertStatus500(() -> wrapper.createConfigMap(new V1ConfigMap()));
        assertStatus500(() -> wrapper.readTaskmasterJob("task-1"));
        assertStatus500(() -> wrapper.getSingleTaskOutputFilerJob("task-1"));
        assertStatus500(() -> wrapper.listSingleJobPods(job));
        assertStatus500(() -> wrapper.listAllJobPods());
        assertStatus500(() -> wrapper.labelJobAsCancelled("task-1"));
        assertStatus500(() -> wrapper.cancelJobBeforeStart("task-1"));
        assertStatus500(() -> wrapper.labelPodAsCancelled("pod-1"));
    }

    private static void assertStatus500(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        KubernetesException.class,
                        e -> assertThat(e.getApiException().getCode()).isEqualTo(500));
    }
}
