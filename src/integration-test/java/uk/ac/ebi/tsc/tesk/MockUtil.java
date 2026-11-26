package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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
import org.springframework.http.HttpStatus;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

class MockUtil {

    private static final Gson GSON = new JSON().getGson();

    /**
     * The caller without authentication (noauth): Spring Security's anonymous user, the creator of
     * every task created without authentication, who gets, lists and cancels only those
     */
    static final String NOAUTH_USER = "anonymousUser";

    /** The label selector of the taskmaster jobs of the user {@code userId} */
    static String ownTaskmastersSelector(String userId) {
        return "job-type=taskmaster,creator-user-hash=" + CreatorLabels.hash(userId);
    }

    /** Stamps the job as created by the user {@code userId}, as TESK API does */
    static V1Job createdBy(V1Job job, String userId) {
        CreatorLabels.put(job.getMetadata(), userId);
        return job;
    }

    /**
     * The taskmasters of list/taskmasters.json (of the users 123 and 124), all as created by the
     * user {@code userId}
     */
    static String taskmastersOf(String userId) throws java.io.IOException {
        String json =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of(
                                "src/integration-test/resources/__files/list/taskmasters.json"));
        for (String creator : new String[] {"123", "124"}) {
            json =
                    json.replace(CreatorLabels.hash(creator), CreatorLabels.hash(userId))
                            .replace(
                                    "\"creator-user-id\": \"" + creator + "\"",
                                    "\"creator-user-id\": \"" + userId + "\"");
        }
        return json;
    }

    static void mockGetTaskKubernetesResponses(WireMockExtension mockKubernetes) {
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=taskmaster-name%3Dtask-123%2Cjob-type%3Dexecutor")
                        .willReturn(aResponse().withBodyFile("task-123/executor.json")));
        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs/task-123")
                        .willReturn(aResponse().withBodyFile("task-123/taskmaster.json")));
        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs/task-123-outputs-filer")
                        .willReturn(aResponse().withStatus(HttpStatus.NOT_FOUND.value())));

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
     * Lets task-123 be a task of the user {@code userId} (replaces the taskmaster of {@link
     * #mockGetTaskKubernetesResponses}, as WireMock prefers the stub added last)
     */
    static void mockOwnTaskKubernetesResponses(WireMockExtension mockKubernetes, String userId) {
        mockTaskKubernetesResponses(mockKubernetes, taskmaster(userId));
    }

    /**
     * Lets task-123 be a task created before the hash label, with only the old label {@code
     * creator-user-id} and a group label (issue #119): nobody's task now, the group label is
     * ignored
     */
    static void mockLegacyTaskKubernetesResponses(
            WireMockExtension mockKubernetes, String legacyUserLabel) {
        V1ObjectMeta metadata =
                new V1ObjectMeta()
                        .name("task-123")
                        .putLabelsItem("job-type", "taskmaster")
                        .putLabelsItem("creator-user-id", legacyUserLabel)
                        .putLabelsItem("creator-group-name", "TEST");
        mockTaskKubernetesResponses(mockKubernetes, taskmaster(metadata));
    }

    /** The taskmaster's job task-123, created by {@code userId} */
    static V1Job taskmaster(String userId) {
        V1ObjectMeta metadata =
                new V1ObjectMeta().name("task-123").putLabelsItem("job-type", "taskmaster");
        CreatorLabels.put(metadata, userId);
        return taskmaster(metadata);
    }

    static V1Job taskmaster(V1ObjectMeta metadata) {
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

    private static void mockTaskKubernetesResponses(WireMockExtension mockKubernetes, V1Job job) {
        mockGetTaskKubernetesResponses(mockKubernetes);
        mockKubernetes.givenThat(
                WireMock.get("/apis/batch/v1/namespaces/default/jobs/task-123")
                        .willReturn(okJson(GSON.toJson(job))));
    }

    /** A list of taskmasters' jobs, as Kubernetes returns it */
    static String jobList(V1Job... jobs) {
        return GSON.toJson(new V1JobList().metadata(new V1ListMeta()).items(Arrays.asList(jobs)));
    }

    static void mockListTaskKubernetesResponses(WireMockExtension mockKubernetes) {
        mockKubernetes.givenThat(
                WireMock.get(
                                "/apis/batch/v1/namespaces/default/jobs?labelSelector=job-type%3Dexecutor")
                        .willReturn(aResponse().withBodyFile("list/executors.json")));

        // no filer jobs: neither of the listed tasks (by the label taskmaster-name) nor unlabelled
        mockKubernetes.givenThat(
                WireMock.get(urlPathEqualTo("/apis/batch/v1/namespaces/default/jobs"))
                        .withQueryParam("labelSelector", matching("!job-type,.*"))
                        .willReturn(okJson("{\n" + "  \"items\": []\n" + "}")));

        mockKubernetes.givenThat(
                WireMock.get("/api/v1/namespaces/default/pods?labelSelector=job-name")
                        .willReturn(aResponse().withBodyFile("list/pods.json")));
        mockKubernetes.givenThat(
                WireMock.get(urlPathMatching("/api/v1/namespaces/default/pods/([-a-z0-9]+)/log"))
                        .willReturn(ok("hello!")));
    }
}
