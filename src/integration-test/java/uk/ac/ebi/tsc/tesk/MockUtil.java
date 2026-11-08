package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.gson.Gson;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.Arrays;
import org.apache.http.HttpStatus;
import uk.ac.ebi.tsc.tesk.k8s.constant.Constants;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

class MockUtil {

    private static final Gson GSON = new JSON().getGson();

    static void mockGetTaskKubernetesResponses(WireMockRule mockKubernetes) {
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=taskmaster-name%3Dtask-123")
                        .willReturn(aResponse().withBodyFile("task-123/executor.json")));
        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs/task-123")
                        .willReturn(aResponse().withBodyFile("task-123/taskmaster.json")));
        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs/task-123-outputs-filer")
                        .willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));

        mockKubernetes.givenThat(
                WireMock.get(
                                "/api/v1/namespaces/default/pods?labelSelector=controller-uid%3D24a0504a-4a2b-11e8-a06f-fa163ecf0042")
                        .willReturn(aResponse().withBodyFile("task-123/taskmaster_pods.json")));
        mockKubernetes.givenThat(
                WireMock.get(
                                "/api/v1/namespaces/default/pods?labelSelector=controller-uid%3D25f89bbb-4a2b-11e8-a06f-fa163ecf0042")
                        .willReturn(aResponse().withBodyFile("task-123/executor_pods.json")));
        mockKubernetes.givenThat(
                WireMock.get("/api/v1/namespaces/default/pods/pod-123/log")
                        .willReturn(ok("hello!")));
        mockKubernetes.givenThat(
                WireMock.get("/api/v1/namespaces/default/pods/pod-ex-123/log")
                        .willReturn(ok("hello executor!")));
    }

    /**
     * Lets task-123 be a task of the user {@code userId} without a group (replaces the taskmaster
     * of {@link #mockGetTaskKubernetesResponses}, as WireMock prefers the stub added last)
     */
    static void mockUngroupedTaskKubernetesResponses(WireMockRule mockKubernetes, String userId) {
        mockTaskKubernetesResponses(mockKubernetes, taskmaster(null, userId));
    }

    /** Lets task-123 be a task of the user {@code userId} for the group {@code group} */
    static void mockGroupTaskKubernetesResponses(
            WireMockRule mockKubernetes, String group, String userId) {
        mockTaskKubernetesResponses(mockKubernetes, taskmaster(group, userId));
    }

    /**
     * Lets task-123 be a task for the group {@code group} created before the hash label, with only
     * the old label {@code creator-user-id} (issue #119)
     */
    static void mockLegacyTaskKubernetesResponses(
            WireMockRule mockKubernetes, String group, String legacyUserLabel) {
        V1ObjectMeta metadata =
                new V1ObjectMeta()
                        .name("task-123")
                        .putLabelsItem("job-type", "taskmaster")
                        .putLabelsItem(Constants.LABEL_USERID_KEY, legacyUserLabel);
        if (group != null) {
            metadata.putLabelsItem(Constants.LABEL_GROUPNAME_KEY, group);
        }
        mockTaskKubernetesResponses(mockKubernetes, taskmaster(metadata));
    }

    /** The taskmaster's job task-123, created by {@code userId} for {@code group} (or none) */
    static V1Job taskmaster(String group, String userId) {
        V1ObjectMeta metadata =
                new V1ObjectMeta().name("task-123").putLabelsItem("job-type", "taskmaster");
        CreatorLabels.put(metadata, userId);
        if (group != null) {
            metadata.putLabelsItem(Constants.LABEL_GROUPNAME_KEY, group);
        }
        return taskmaster(metadata);
    }

    private static V1Job taskmaster(V1ObjectMeta metadata) {
        return new V1Job()
                .metadata(metadata)
                .spec(
                        new V1JobSpec()
                                .selector(
                                        new V1LabelSelector()
                                                .putMatchLabelsItem(
                                                        "controller-uid",
                                                        "24a0504a-4a2b-11e8-a06f-fa163ecf0042")))
                .status(new V1JobStatus().succeeded(1));
    }

    private static void mockTaskKubernetesResponses(WireMockRule mockKubernetes, V1Job job) {
        mockGetTaskKubernetesResponses(mockKubernetes);
        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs/task-123")
                        .willReturn(okJson(GSON.toJson(job))));
    }

    /** A list of taskmasters' jobs, as Kubernetes returns it */
    static String jobList(V1Job... jobs) {
        return GSON.toJson(new V1JobList().metadata(new V1ListMeta()).items(Arrays.asList(jobs)));
    }

    static void mockListTaskKubernetesResponses(WireMockRule mockKubernetes) {
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dexecutor")
                        .willReturn(aResponse().withBodyFile("list/executors.json")));

        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs?labelSelector=%21job-type")
                        .willReturn(okJson("{\n" + "  \"items\": []\n" + "}")));

        mockKubernetes.givenThat(
                WireMock.get("/api/v1/namespaces/default/pods?labelSelector=job-name")
                        .willReturn(aResponse().withBodyFile("list/pods.json")));
        mockKubernetes.givenThat(
                WireMock.get(urlPathMatching("/api/v1/namespaces/default/pods/([-a-z0-9]+)/log"))
                        .willReturn(ok("hello!")));
    }
}
