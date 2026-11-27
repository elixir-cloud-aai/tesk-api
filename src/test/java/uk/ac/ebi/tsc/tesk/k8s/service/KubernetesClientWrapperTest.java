package uk.ac.ebi.tsc.tesk.k8s.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_USERID_KEY;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageTokenException;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;

@ExtendWith(SpringExtension.class)
@TestPropertySource(properties = {"tesk.api.k8s.namespace = test-namespace"})
public class KubernetesClientWrapperTest {

    @Value("${tesk.api.k8s.namespace}")
    private String namespace;

    @MockitoBean private BatchV1Api batchApi;

    @MockitoBean private CoreV1Api coreApi;

    private KubernetesClientWrapper wrapper;

    // The requests of the Kubernetes client: their setters return the request itself, only
    // execute() needs to be stubbed
    private BatchV1Api.APIcreateNamespacedJobRequest createJob;

    private BatchV1Api.APIdeleteNamespacedJobRequest deleteJob;

    private BatchV1Api.APIreadNamespacedJobRequest readJob;

    private BatchV1Api.APIlistNamespacedJobRequest listJobs;

    private BatchV1Api.APIpatchNamespacedJobRequest patchJob;

    private CoreV1Api.APIcreateNamespacedConfigMapRequest createConfigMap;

    private CoreV1Api.APIcreateNamespacedSecretRequest createSecret;

    private CoreV1Api.APIlistNamespacedPodRequest listPods;

    @BeforeEach
    public void setUp() {
        createJob = request(BatchV1Api.APIcreateNamespacedJobRequest.class);
        given(batchApi.createNamespacedJob(any(), any())).willReturn(createJob);
        deleteJob = request(BatchV1Api.APIdeleteNamespacedJobRequest.class);
        given(batchApi.deleteNamespacedJob(any(), any())).willReturn(deleteJob);
        readJob = request(BatchV1Api.APIreadNamespacedJobRequest.class);
        given(batchApi.readNamespacedJob(any(), any())).willReturn(readJob);
        listJobs = request(BatchV1Api.APIlistNamespacedJobRequest.class);
        given(batchApi.listNamespacedJob(any())).willReturn(listJobs);
        patchJob = request(BatchV1Api.APIpatchNamespacedJobRequest.class);
        given(batchApi.patchNamespacedJob(any(), any(), any())).willReturn(patchJob);
        createConfigMap = request(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        given(coreApi.createNamespacedConfigMap(any(), any())).willReturn(createConfigMap);
        createSecret = request(CoreV1Api.APIcreateNamespacedSecretRequest.class);
        given(coreApi.createNamespacedSecret(any(), any())).willReturn(createSecret);
        listPods = request(CoreV1Api.APIlistNamespacedPodRequest.class);
        given(coreApi.listNamespacedPod(any())).willReturn(listPods);

        this.wrapper = new KubernetesClientWrapper(batchApi, batchApi, coreApi, coreApi, namespace);
    }

    private static <T> T request(Class<T> type) {
        return mock(type, Answers.RETURNS_SELF);
    }

    /**
     * A job cancelled before its pod runs is labelled as cancelled and gets parallelism 0 and an
     * active deadline of 1 second, in one patch, so that Kubernetes removes its pods and fails it
     * (issue #126)
     */
    @Test
    public void cancelJobBeforeStart() throws ApiException {
        org.mockito.ArgumentCaptor<io.kubernetes.client.custom.V1Patch> patch =
                org.mockito.ArgumentCaptor.forClass(io.kubernetes.client.custom.V1Patch.class);
        wrapper.cancelJobBeforeStart("task-123");
        Mockito.verify(batchApi)
                .patchNamespacedJob(eq("task-123"), eq("test-namespace"), patch.capture());
        Mockito.verify(patchJob).execute();
        assertEquals(
                "{\"metadata\":{\"labels\":{\"task-status\":\"Cancelled\"}},"
                        + "\"spec\":{\"parallelism\":0,\"activeDeadlineSeconds\":1}}",
                patch.getValue().getValue());
    }

    @Test
    public void cancelJobBeforeStart_refused() throws ApiException {
        given(patchJob.execute()).willThrow(new ApiException(403, "forbidden"));
        assertThrows(KubernetesException.class, () -> wrapper.cancelJobBeforeStart("task-123"));
    }

    /** A ConfigMap is checked by creating it in a dry run */
    @Test
    public void checkConfigMap() throws ApiException {
        V1ConfigMap map = new V1ConfigMap().metadata(new V1ObjectMeta().name("123"));
        wrapper.checkConfigMap(map);
        Mockito.verify(coreApi).createNamespacedConfigMap(eq("test-namespace"), same(map));
        Mockito.verify(createConfigMap).dryRun("All");
        Mockito.verify(createConfigMap).execute();
    }

    @Test
    public void checkConfigMap_refused() throws ApiException {
        given(createConfigMap.execute()).willThrow(new ApiException(422, "too large"));
        assertThrows(KubernetesException.class, () -> wrapper.checkConfigMap(new V1ConfigMap()));
    }

    /**
     * An admission webhook that doesn't support dry runs makes Kubernetes refuse every dry run
     * (400): the check is skipped, so that tasks can still be created
     */
    @Test
    public void checkConfigMap_webhookWithoutDryRun() throws ApiException {
        given(createConfigMap.execute())
                .willThrow(
                        new ApiException(
                                "",
                                400,
                                null,
                                "{\"kind\":\"Status\",\"message\":\"admission webhook"
                                        + " \\\"policy.example.org\\\" does not support dry"
                                        + " run\",\"reason\":\"BadRequest\",\"code\":400}"));
        wrapper.checkConfigMap(new V1ConfigMap());
    }

    /** A 400 of the dry run without a Status to tell the cause isn't skipped */
    @Test
    public void checkConfigMap_badRequestWithoutBody() throws ApiException {
        given(createConfigMap.execute()).willThrow(new ApiException("", 400, null, null));
        assertThrows(KubernetesException.class, () -> wrapper.checkConfigMap(new V1ConfigMap()));
    }

    /** Other 400s of the dry run aren't skipped */
    @Test
    public void checkConfigMap_otherBadRequest() throws ApiException {
        given(createConfigMap.execute())
                .willThrow(new ApiException("", 400, null, "{\"message\":\"invalid\"}"));
        assertThrows(KubernetesException.class, () -> wrapper.checkConfigMap(new V1ConfigMap()));
    }

    @Test
    public void checkSecret() throws ApiException {
        V1Secret secret = new V1Secret().metadata(new V1ObjectMeta().name("task-123-ex-00"));
        wrapper.checkSecret(secret);
        Mockito.verify(coreApi).createNamespacedSecret(eq("test-namespace"), same(secret));
        Mockito.verify(createSecret).dryRun("All");
        Mockito.verify(createSecret).execute();
    }

    @Test
    public void checkSecret_webhookWithoutDryRun() throws ApiException {
        given(createSecret.execute())
                .willThrow(
                        new ApiException(
                                "",
                                400,
                                null,
                                "{\"message\":\"admission webhook does not support dry run\"}"));
        wrapper.checkSecret(new V1Secret());
    }

    @Test
    public void checkSecret_refused() throws ApiException {
        given(createSecret.execute()).willThrow(new ApiException(403, "private-token"));
        try {
            wrapper.checkSecret(new V1Secret());
            fail("Expected KubernetesException");
        } catch (KubernetesException e) {
            assertEquals(403, e.getApiException().getCode());
            assertEquals("Kubernetes rejected an image pull Secret (HTTP 403)", e.getMessage());
        }
    }

    @Test
    public void createSecret() throws ApiException {
        V1Secret secret = new V1Secret().metadata(new V1ObjectMeta().name("task-123-ex-00"));
        given(createSecret.execute()).willReturn(secret);
        assertThat(wrapper.createSecret(secret), is(secret));
        Mockito.verify(coreApi).createNamespacedSecret("test-namespace", secret);
        Mockito.verify(createSecret, Mockito.never()).dryRun(any());
    }

    @Test
    public void createSecret_refusedDoesNotExposeCredentials() throws ApiException {
        given(createSecret.execute()).willThrow(new ApiException(422, "private-token"));
        try {
            wrapper.createSecret(new V1Secret());
            fail("Expected KubernetesException");
        } catch (KubernetesException e) {
            assertEquals(422, e.getApiException().getCode());
            assertEquals("Kubernetes rejected an image pull Secret (HTTP 422)", e.getMessage());
        }
    }

    @Test
    public void createJob() throws ApiException {
        V1Job inJob = new V1Job().metadata(new V1ObjectMeta().name("test job"));
        V1Job outJob =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("test job")
                                        .creationTimestamp(OffsetDateTime.now()));
        given(createJob.execute()).willReturn(outJob);
        V1Job result = wrapper.createJob(inJob);
        assertThat(result, is(outJob));
        Mockito.verify(batchApi).createNamespacedJob("test-namespace", inJob);
    }

    @Test
    public void readTaskmasterJob() throws ApiException {
        V1Job job =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("test job")
                                        .putLabelsItem("job-type", "taskmaster"));
        given(readJob.execute()).willReturn(job);
        V1Job result = wrapper.readTaskmasterJob("123");
        assertThat(result, is(job));
        Mockito.verify(batchApi).readNamespacedJob("123", "test-namespace");
    }

    @Test
    public void readTaskmasterJob_wrongType() throws Exception {
        V1Job job =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("test job")
                                        .putLabelsItem("job-type", "executor"));
        given(readJob.execute()).willReturn(job);
        assertThrows(TaskNotFoundException.class, () -> wrapper.readTaskmasterJob("123"));
    }

    @Test
    public void readTaskmasterJob_noType() throws Exception {
        V1Job job =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("test job")
                                        .putLabelsItem("other", "taskmaster"));
        given(readJob.execute()).willReturn(job);
        assertThrows(TaskNotFoundException.class, () -> wrapper.readTaskmasterJob("123"));
    }

    /** A job without labels isn't a taskmaster's job: 404, not 500 (issue #125) */
    @Test
    public void readTaskmasterJob_noLabels() throws Exception {
        V1Job job = new V1Job().metadata(new V1ObjectMeta().name("123"));
        given(readJob.execute()).willReturn(job);
        assertThrows(TaskNotFoundException.class, () -> wrapper.readTaskmasterJob("123"));
    }

    /** A job without metadata isn't a taskmaster's job either (issue #125) */
    @Test
    public void readTaskmasterJob_noMetadata() throws Exception {
        given(readJob.execute()).willReturn(new V1Job());
        assertThrows(TaskNotFoundException.class, () -> wrapper.readTaskmasterJob("123"));
    }

    @Test
    public void readTaskmasterJob_noJob() throws Exception {
        given(readJob.execute())
                .willThrow(new ApiException(HttpStatus.NOT_FOUND.value(), "No job"));
        assertThrows(TaskNotFoundException.class, () -> wrapper.readTaskmasterJob("123"));
    }

    /** A taskmaster job that Kubernetes is deleting (e.g. after its TTL) is gone (issue #117) */
    @Test
    public void readTaskmasterJob_beingDeleted() throws Exception {
        V1Job job =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("123")
                                        .deletionTimestamp(OffsetDateTime.now())
                                        .putLabelsItem("job-type", "taskmaster"));
        given(readJob.execute()).willReturn(job);
        assertThrows(TaskNotFoundException.class, () -> wrapper.readTaskmasterJob("123"));
    }

    /** Taskmaster jobs that Kubernetes is deleting aren't listed (issue #117) */
    @Test
    public void listAllTaskmasterJobsForUser_skipsJobsBeingDeleted() throws ApiException {
        V1Job kept = job("123");
        kept.getMetadata().name("kept");
        V1Job deleted = job("123");
        deleted.getMetadata().name("deleted").deletionTimestamp(OffsetDateTime.now());
        given(listJobs.execute())
                .willReturn(
                        new V1JobList()
                                .metadata(new V1ListMeta()._continue("next"))
                                .items(Arrays.asList(deleted, kept)));
        V1JobList result =
                wrapper.listAllTaskmasterJobsForUser(null, null, User.builder("123").build());
        assertThat(result.getItems(), is(Arrays.asList(kept)));
        assertThat(result.getMetadata().getContinue(), is("next"));
    }

    /** A job is deleted with its pods, in the background */
    @Test
    public void deleteJob() throws ApiException {
        wrapper.deleteJob("123");
        Mockito.verify(batchApi).deleteNamespacedJob("123", "test-namespace");
        Mockito.verify(deleteJob).propagationPolicy("Background");
        Mockito.verify(deleteJob).execute();
    }

    @Test
    public void deleteJob_fails() throws ApiException {
        given(deleteJob.execute())
                .willThrow(new ApiException(HttpStatus.FORBIDDEN.value(), "forbidden"));
        assertThrows(KubernetesException.class, () -> wrapper.deleteJob("123"));
    }

    /**
     * Every caller lists only its own jobs: by the hash label of its ID, then exactly by the
     * annotation (IDs with the same hash, or that the old label encoding merged, are told apart;
     * issues #119 and #225)
     */
    @Test
    public void listAllTaskmasterJobsForUser_ownJobsOnly() throws ApiException {
        listJobsReturning(job("a@b"), job("a_b"), job("c@d"), job("a@b"));
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        V1JobList result =
                wrapper.listAllTaskmasterJobsForUser(null, null, User.builder("a@b").build());
        verifyListJobs("test-namespace", argument);
        assertThat(
                argument.getValue(),
                is("job-type=taskmaster,creator-user-hash=" + CreatorLabels.hash("a@b")));
        assertThat(result.getItems(), is(Arrays.asList(job("a@b"), job("a@b"))));
    }

    /** Service clients list their own jobs the same way */
    @Test
    public void listAllTaskmasterJobsForUser_serviceClient() throws ApiException {
        listJobsReturning(job("client"), job("other"));
        User client = User.builder("client").serviceClient(true).build();
        assertThat(
                wrapper.listAllTaskmasterJobsForUser(null, null, client).getItems(),
                is(Arrays.asList(job("client"))));
    }

    /**
     * Jobs without the hash label and the annotation (e.g. with only the old label, or no labels)
     * belong to nobody, and jobs of a group are no longer listed to its members or managers
     */
    @Test
    public void listAllTaskmasterJobsForUser_jobsWithoutCreatorAreNobodys() throws ApiException {
        V1ObjectMeta annotatedOnly = new V1ObjectMeta().putAnnotationsItem(ANN_USERID_KEY, "a@b");
        V1ObjectMeta oldLabelOnly = new V1ObjectMeta().putLabelsItem("creator-user-id", "a_b");
        V1ObjectMeta othersInGroup = new V1ObjectMeta().putLabelsItem("creator-group-name", "TEST");
        CreatorLabels.put(othersInGroup, "c@d");
        listJobsReturning(
                new V1Job().metadata(new V1ObjectMeta()),
                new V1Job().metadata(annotatedOnly),
                new V1Job().metadata(oldLabelOnly),
                new V1Job().metadata(othersInGroup),
                job("a@b"));
        assertThat(
                wrapper.listAllTaskmasterJobsForUser(null, null, User.builder("a@b").build())
                        .getItems(),
                is(Arrays.asList(job("a@b"))));
    }

    /**
     * The list scope {@code all} of a decision service: the jobs of every owner, without an owner
     * selector, but only those with an exact owner (others count as missing)
     */
    @Test
    public void listAllOwnedTaskmasterJobs() throws ApiException {
        V1ObjectMeta annotatedOnly = new V1ObjectMeta().putAnnotationsItem(ANN_USERID_KEY, "a@b");
        listJobsReturning(
                job("a@b"),
                new V1Job().metadata(new V1ObjectMeta()),
                new V1Job().metadata(annotatedOnly),
                job("c@d"));
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        V1JobList result = wrapper.listAllOwnedTaskmasterJobs(null, null);
        verifyListJobs("test-namespace", argument);
        assertThat(argument.getValue(), is("job-type=taskmaster"));
        assertThat(result.getItems(), is(Arrays.asList(job("a@b"), job("c@d"))));
    }

    @Test
    public void listSingleTaskExecutorJobs() throws ApiException {
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        wrapper.listSingleTaskExecutorJobs("123");
        verifyListJobs("test-namespace", argument);
        assertThat(argument.getValue(), is("taskmaster-name=123,job-type=executor"));
    }

    @Test
    public void getSingleTaskOutputFilerJob() throws ApiException {
        V1Job job = new V1Job().metadata(new V1ObjectMeta().name("test job"));
        given(readJob.execute()).willReturn(job);
        Optional<V1Job> result = wrapper.getSingleTaskOutputFilerJob("123");
        assertThat(result, is(Optional.of(job)));
        Mockito.verify(batchApi).readNamespacedJob("123-outputs-filer", "test-namespace");
    }

    @Test
    public void getSingleTaskOutputFilerJob_noJob() throws ApiException {
        given(readJob.execute())
                .willThrow(new ApiException(HttpStatus.NOT_FOUND.value(), "No Job"));
        Optional<V1Job> result = wrapper.getSingleTaskOutputFilerJob("123");
        assertThat(result, is(Optional.empty()));
    }

    @Test
    public void listAllTaskExecutorJobs() throws ApiException {
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        wrapper.listAllTaskExecutorJobs();
        verifyListJobs("test-namespace", argument);
        assertThat(argument.getValue(), is("job-type=executor"));
    }

    /**
     * The filer jobs of the listed tasks are selected by their label {@code taskmaster-name}
     * (tesk-core#147), not all filer jobs of the namespace (issue #177)
     */
    @Test
    public void listFilerJobsOfTasks() throws ApiException {
        V1Job inputs = new V1Job().metadata(new V1ObjectMeta().name("task-1-inputs-filer"));
        V1Job outputs = new V1Job().metadata(new V1ObjectMeta().name("task-1-outputs-filer"));
        given(listJobs.execute())
                .willReturn(new V1JobList().addItemsItem(inputs).addItemsItem(outputs));
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        V1JobList result = wrapper.listFilerJobsOfTasks(Arrays.asList("task-1", "task-2"));
        verifyListJobs("test-namespace", argument);
        assertThat(argument.getValue(), is("!job-type,taskmaster-name in (task-1,task-2)"));
        assertThat(result.getItems(), is(Arrays.asList(inputs, outputs)));
    }

    /**
     * Many tasks are selected in batches, so that the selector in the request's URL stays short,
     * and the batches' jobs are returned together: 101 tasks in batches of 100 and 1 (issue #177)
     */
    @Test
    public void listFilerJobsOfTasks_inBatches() throws ApiException {
        List<String> taskIds =
                IntStream.range(0, 101).mapToObj(i -> "task-" + i).collect(Collectors.toList());
        V1Job first = new V1Job().metadata(new V1ObjectMeta().name("task-0-outputs-filer"));
        V1Job last = new V1Job().metadata(new V1ObjectMeta().name("task-100-outputs-filer"));
        given(listJobs.execute())
                .willReturn(
                        new V1JobList().addItemsItem(first), new V1JobList().addItemsItem(last));
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        V1JobList result = wrapper.listFilerJobsOfTasks(taskIds);
        Mockito.verify(listJobs, Mockito.times(2)).labelSelector(argument.capture());
        assertThat(
                argument.getAllValues(),
                is(
                        Arrays.asList(
                                "!job-type,taskmaster-name in ("
                                        + String.join(",", taskIds.subList(0, 100))
                                        + ")",
                                "!job-type,taskmaster-name in (task-100)")));
        assertThat(result.getItems(), is(Arrays.asList(first, last)));
    }

    /** No task, no request */
    @Test
    public void listFilerJobsOfTasks_noTasks() {
        V1JobList result = wrapper.listFilerJobsOfTasks(Collections.emptyList());
        assertThat(result.getItems(), is(Collections.emptyList()));
        Mockito.verifyNoInteractions(batchApi);
    }

    /**
     * The filer jobs of tasks started by taskmasters before tesk-core#147 have no label {@code
     * taskmaster-name}: they are listed by the absence of both labels (issue #177)
     */
    @Test
    public void listUnlabelledFilerJobs() throws ApiException {
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        wrapper.listUnlabelledFilerJobs();
        verifyListJobs("test-namespace", argument);
        assertThat(argument.getValue(), is("!job-type,!taskmaster-name"));
    }

    @Test
    public void listSingleJobPods() throws ApiException {
        V1Job job =
                new V1Job()
                        .metadata(new V1ObjectMeta().name("test job"))
                        .spec(
                                new V1JobSpec()
                                        .selector(
                                                new V1LabelSelector()
                                                        .putMatchLabelsItem("label1", "value1")
                                                        .putMatchLabelsItem("labelA", "valueB")));
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        wrapper.listSingleJobPods(job);
        Mockito.verify(coreApi).listNamespacedPod("test-namespace");
        Mockito.verify(listPods).labelSelector(argument.capture());
        Mockito.verify(listPods).execute();
        assertThat(argument.getValue(), is("label1=value1,labelA=valueB"));
    }

    @Test
    public void listAllJobPods() throws ApiException {
        wrapper.listAllJobPods();
        Mockito.verify(coreApi).listNamespacedPod("test-namespace");
        Mockito.verify(listPods).labelSelector("job-name");
        Mockito.verify(listPods).execute();
    }

    /**
     * Verifies that the jobs were listed once, in the namespace, without a page token or limit, and
     * captures the label selector
     */
    private void verifyListJobs(String namespace, ArgumentCaptor<String> labelSelector)
            throws ApiException {
        Mockito.verify(batchApi).listNamespacedJob(namespace);
        Mockito.verify(listJobs)._continue(isNull());
        Mockito.verify(listJobs).labelSelector(labelSelector.capture());
        Mockito.verify(listJobs).limit(isNull());
        Mockito.verify(listJobs).execute();
    }

    /** A job created by the user, labelled and annotated as TESK API does */
    private static V1Job job(String userId) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        CreatorLabels.put(metadata, userId);
        return new V1Job().metadata(metadata);
    }

    private V1JobList listJobsReturning(V1Job... jobs) throws ApiException {
        V1JobList jobList = new V1JobList().items(Arrays.asList(jobs));
        given(listJobs.execute()).willReturn(jobList);
        return jobList;
    }

    /** Kubernetes answers an expired continue token with 410 Gone (issue #114) */
    @Test
    public void listAllTaskmasterJobsForUser_expiredPageToken() throws ApiException {
        this.assertInvalidPageToken(HttpStatus.GONE.value());
    }

    /** Kubernetes answers a malformed continue token with 400 Bad Request (issue #114) */
    @Test
    public void listAllTaskmasterJobsForUser_malformedPageToken() throws ApiException {
        this.assertInvalidPageToken(HttpStatus.BAD_REQUEST.value());
    }

    private void assertInvalidPageToken(int code) throws ApiException {
        this.listJobsFails(
                code,
                "{\"kind\":\"Status\",\"message\":\"continue key is not valid: secret-token\"}");
        try {
            wrapper.listAllTaskmasterJobsForUser("secret-token", null, User.builder("123").build());
            fail("InvalidPageTokenException expected");
        } catch (InvalidPageTokenException e) {
            assertTrue(e.getMessage().contains("page_token"));
            assertFalse(e.getMessage().contains("secret-token"));
        }
        Mockito.verify(listJobs)._continue("secret-token");
    }

    /** The page token and the page size are passed on to Kubernetes */
    @Test
    public void listAllTaskmasterJobsForUser_page() throws ApiException {
        listJobsReturning(job("123"));
        wrapper.listAllTaskmasterJobsForUser("token", 10, User.builder("123").build());
        Mockito.verify(listJobs)._continue("token");
        Mockito.verify(listJobs).limit(10);
    }

    /** Without a page token, a 400 of Kubernetes stays a server error */
    @Test
    public void listAllTaskmasterJobsForUser_badRequestWithoutPageToken() throws ApiException {
        this.listJobsFails(HttpStatus.BAD_REQUEST.value(), "bad selector");
        assertThrows(
                KubernetesException.class,
                () ->
                        wrapper.listAllTaskmasterJobsForUser(
                                null, null, User.builder("123").build()));
    }

    /** A 400 of Kubernetes that isn't about the continue token stays a server error */
    @Test
    public void listAllTaskmasterJobsForUser_otherBadRequestWithPageToken() throws ApiException {
        this.listJobsFails(
                HttpStatus.BAD_REQUEST.value(),
                "{\"kind\":\"Status\",\"message\":\"unable to parse requirement\"}");
        assertThrows(
                KubernetesException.class,
                () ->
                        wrapper.listAllTaskmasterJobsForUser(
                                "token", null, User.builder("123").build()));
    }

    /** A 400 of Kubernetes without a Status to tell the cause stays a server error */
    @Test
    public void listAllTaskmasterJobsForUser_badRequestWithoutBodyWithPageToken()
            throws ApiException {
        this.listJobsFails(HttpStatus.BAD_REQUEST.value(), null);
        assertThrows(
                KubernetesException.class,
                () ->
                        wrapper.listAllTaskmasterJobsForUser(
                                "token", null, User.builder("123").build()));
    }

    /** Other errors of Kubernetes stay server errors, also with a page token */
    @Test
    public void listAllTaskmasterJobsForUser_serverErrorWithPageToken() throws ApiException {
        this.listJobsFails(HttpStatus.INTERNAL_SERVER_ERROR.value(), "error");
        assertThrows(
                KubernetesException.class,
                () ->
                        wrapper.listAllTaskmasterJobsForUser(
                                "token", null, User.builder("123").build()));
    }

    /**
     * Lets Kubernetes answer the list request with an error and the response body (a Status object)
     */
    private void listJobsFails(int code, String responseBody) throws ApiException {
        given(listJobs.execute()).willThrow(new ApiException("", code, null, responseBody));
    }
}
