package uk.ac.ebi.tsc.tesk.accessmatrix;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

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
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * A mock Kubernetes API with running tasks of given owners: each task is a taskmaster's job with
 * one running pod and no executors yet, so that it can be got in every view and cancelled. Lists of
 * taskmasters' jobs honour the owner's hash label in the selector, as Kubernetes does (all tasks
 * without one). Unknown jobs are 404; creating a task succeeds.
 */
final class MockCluster {

    static final int PORT = 9000;
    static final String JOBS = "/apis/batch/v1/namespaces/default/jobs";
    static final String PODS = "/api/v1/namespaces/default/pods";

    /** The label and annotation of a task's team (the group model, until slice T2) */
    static final String TEAM_LABEL = "creator-group-name";

    /** The name of the job that creating a task makes */
    static final String CREATED = "task-created";

    private static final Gson GSON = new JSON().getGson();

    private final WireMockExtension kubernetes;

    /** The tasks by name, with their owners */
    private final Map<String, String> tasks = new LinkedHashMap<>();

    /** The teams of the tasks that have one, by name */
    private final Map<String, String> teams = new LinkedHashMap<>();

    MockCluster(WireMockExtension kubernetes) {
        this.kubernetes = kubernetes;
    }

    /**
     * Adds a running task of the owner, as TESK stamps it on create, for a team (or none): today,
     * TESK puts the tasks of team members into a team (its label {@code creator-group-name}); the
     * redesign drops teams (slice T2) and ignores the label of older tasks
     */
    MockCluster task(String name, String owner, String team) {
        this.tasks.put(name, owner);
        if (team != null) {
            this.teams.put(name, team);
        }
        return this;
    }

    /** Stubs the Kubernetes API with the tasks added so far */
    void stub() {
        // anything else: not found, or nothing listed
        kubernetes.givenThat(
                get(urlPathMatching(JOBS + "/[^/]+"))
                        .atPriority(10)
                        .willReturn(aResponse().withStatus(404)));
        kubernetes.givenThat(
                get(urlPathEqualTo(JOBS)).atPriority(10).willReturn(okJson(jobList(List.of()))));
        kubernetes.givenThat(
                get(urlPathEqualTo(PODS)).atPriority(10).willReturn(okJson(podList(List.of()))));
        kubernetes.givenThat(
                get(urlPathMatching(PODS + "/[^/]+/log")).willReturn(ok("log of the pod")));
        // creating a task: dry runs and the objects themselves
        kubernetes.givenThat(
                post(urlPathEqualTo(JOBS))
                        .willReturn(okJson(GSON.toJson(job(CREATED, "creator", null)))));
        kubernetes.givenThat(
                post(urlPathEqualTo("/api/v1/namespaces/default/configmaps"))
                        .willReturn(okJson("{\"metadata\":{\"name\":\"" + CREATED + "\"}}")));

        List<V1Job> all = new ArrayList<>();
        List<V1Pod> allPods = new ArrayList<>();
        Map<String, List<V1Job>> byOwner = new LinkedHashMap<>();
        this.tasks.forEach((name, owner) -> {
            V1Job job = job(name, owner, this.teams.get(name));
            V1Pod pod = pod(name);
            all.add(job);
            allPods.add(pod);
            byOwner.computeIfAbsent(owner, o -> new ArrayList<>()).add(job);
            kubernetes.givenThat(
                    get(urlPathEqualTo(JOBS + "/" + name)).willReturn(okJson(GSON.toJson(job))));
            kubernetes.givenThat(
                    get(urlPathEqualTo(PODS))
                            .withQueryParam(
                                    "labelSelector", matching("controller-uid=" + uid(name)))
                            .willReturn(okJson(podList(List.of(pod)))));
            kubernetes.givenThat(
                    patch(urlPathEqualTo(JOBS + "/" + name)).willReturn(okJson(GSON.toJson(job))));
            kubernetes.givenThat(
                    patch(urlPathEqualTo(PODS + "/" + pod.getMetadata().getName()))
                            .willReturn(okJson(GSON.toJson(pod))));
        });
        // lists of taskmasters' jobs: of one owner by the hash label, of nobody for an unknown
        // hash, and all without one
        byOwner.forEach((owner, jobs) ->
                kubernetes.givenThat(
                        get(urlPathEqualTo(JOBS))
                                .withQueryParam(
                                        "labelSelector",
                                        matching(
                                                "job-type=taskmaster(,.*)?,creator-user-hash="
                                                        + CreatorLabels.hash(owner)
                                                        + "(,.*)?"))
                                .atPriority(1)
                                .willReturn(okJson(jobList(jobs)))));
        kubernetes.givenThat(
                get(urlPathEqualTo(JOBS))
                        .withQueryParam(
                                "labelSelector",
                                matching("job-type=taskmaster(,.*)?,creator-user-hash=.*"))
                        .atPriority(2)
                        .willReturn(okJson(jobList(List.of()))));
        kubernetes.givenThat(
                get(urlPathEqualTo(JOBS))
                        .withQueryParam("labelSelector", matching("job-type=taskmaster.*"))
                        .atPriority(3)
                        .willReturn(okJson(jobList(all))));
        // all pods of all jobs, for lists
        kubernetes.givenThat(
                get(urlPathEqualTo(PODS))
                        .withQueryParam("labelSelector", matching(Pattern.quote("job-name")))
                        .willReturn(okJson(podList(allPods))));
    }

    /** The taskmaster's job of a running task of the owner, for the team (or none) */
    static V1Job job(String name, String owner, String team) {
        V1ObjectMeta metadata =
                new V1ObjectMeta()
                        .name(name)
                        .uid(uid(name))
                        .creationTimestamp(OffsetDateTime.parse("2026-11-25T10:00:00Z"))
                        .putLabelsItem("job-type", "taskmaster");
        CreatorLabels.put(metadata, owner);
        if (team != null) {
            metadata.putLabelsItem(TEAM_LABEL, team).putAnnotationsItem(TEAM_LABEL, team);
        }
        return new V1Job()
                .metadata(metadata)
                .spec(
                        new V1JobSpec()
                                .selector(
                                        new V1LabelSelector()
                                                .putMatchLabelsItem("controller-uid", uid(name))))
                .status(
                        new V1JobStatus()
                                .active(1)
                                .startTime(OffsetDateTime.parse("2026-11-25T10:00:01Z")));
    }

    private static V1Pod pod(String jobName) {
        return new V1Pod()
                .metadata(
                        new V1ObjectMeta()
                                .name(jobName + "-pod")
                                .putLabelsItem("controller-uid", uid(jobName))
                                .putLabelsItem("job-name", jobName))
                .status(new V1PodStatus().phase("Running"));
    }

    /** A UID per job name, as Kubernetes would give it */
    private static String uid(String name) {
        return "uid-" + name;
    }

    static String jobList(List<V1Job> jobs) {
        return GSON.toJson(new V1JobList().metadata(new V1ListMeta()).items(jobs));
    }

    private static String podList(List<V1Pod> pods) {
        return GSON.toJson(new V1PodList().metadata(new V1ListMeta()).items(pods));
    }
}
