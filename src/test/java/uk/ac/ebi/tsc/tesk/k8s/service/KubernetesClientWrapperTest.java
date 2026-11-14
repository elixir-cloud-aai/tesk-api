package uk.ac.ebi.tsc.tesk.k8s.service;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_USERID_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_GROUPNAME_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_LEGACY_USERID_KEY;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageTokenException;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;

@RunWith(SpringRunner.class)
@TestPropertySource(properties = {"tesk.api.k8s.namespace = test-namespace"})
public class KubernetesClientWrapperTest {

    @Value("${tesk.api.k8s.namespace}")
    private String namespace;

    @MockBean private BatchV1Api batchApi;

    @MockBean private CoreV1Api coreApi;

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

    @Before
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

    @Test(expected = KubernetesException.class)
    public void cancelJobBeforeStart_refused() throws ApiException {
        given(patchJob.execute()).willThrow(new ApiException(403, "forbidden"));
        wrapper.cancelJobBeforeStart("task-123");
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

    @Test(expected = KubernetesException.class)
    public void checkConfigMap_refused() throws ApiException {
        given(createConfigMap.execute()).willThrow(new ApiException(422, "too large"));
        wrapper.checkConfigMap(new V1ConfigMap());
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
    @Test(expected = KubernetesException.class)
    public void checkConfigMap_badRequestWithoutBody() throws ApiException {
        given(createConfigMap.execute()).willThrow(new ApiException("", 400, null, null));
        wrapper.checkConfigMap(new V1ConfigMap());
    }

    /** Other 400s of the dry run aren't skipped */
    @Test(expected = KubernetesException.class)
    public void checkConfigMap_otherBadRequest() throws ApiException {
        given(createConfigMap.execute())
                .willThrow(new ApiException("", 400, null, "{\"message\":\"invalid\"}"));
        wrapper.checkConfigMap(new V1ConfigMap());
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

    @Test(expected = TaskNotFoundException.class)
    public void readTaskmasterJob_wrongType() throws Exception {
        V1Job job =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("test job")
                                        .putLabelsItem("job-type", "executor"));
        given(readJob.execute()).willReturn(job);
        wrapper.readTaskmasterJob("123");
    }

    @Test(expected = TaskNotFoundException.class)
    public void readTaskmasterJob_noType() throws Exception {
        V1Job job =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("test job")
                                        .putLabelsItem("other", "taskmaster"));
        given(readJob.execute()).willReturn(job);
        wrapper.readTaskmasterJob("123");
    }

    /** A job without labels isn't a taskmaster's job: 404, not 500 (issue #125) */
    @Test(expected = TaskNotFoundException.class)
    public void readTaskmasterJob_noLabels() throws Exception {
        V1Job job = new V1Job().metadata(new V1ObjectMeta().name("123"));
        given(readJob.execute()).willReturn(job);
        wrapper.readTaskmasterJob("123");
    }

    /** A job without metadata isn't a taskmaster's job either (issue #125) */
    @Test(expected = TaskNotFoundException.class)
    public void readTaskmasterJob_noMetadata() throws Exception {
        given(readJob.execute()).willReturn(new V1Job());
        wrapper.readTaskmasterJob("123");
    }

    @Test(expected = TaskNotFoundException.class)
    public void readTaskmasterJob_noJob() throws Exception {
        given(readJob.execute())
                .willThrow(new ApiException(HttpStatus.NOT_FOUND.value(), "No job"));
        wrapper.readTaskmasterJob("123");
    }

    /** A taskmaster job that Kubernetes is deleting (e.g. after its TTL) is gone (issue #117) */
    @Test(expected = TaskNotFoundException.class)
    public void readTaskmasterJob_beingDeleted() throws Exception {
        V1Job job =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("123")
                                        .deletionTimestamp(OffsetDateTime.now())
                                        .putLabelsItem("job-type", "taskmaster"));
        given(readJob.execute()).willReturn(job);
        wrapper.readTaskmasterJob("123");
    }

    /** Taskmaster jobs that Kubernetes is deleting aren't listed (issue #117) */
    @Test
    public void listAllTaskmasterJobsForUser_skipsJobsBeingDeleted() throws ApiException {
        V1Job kept = new V1Job().metadata(new V1ObjectMeta().name("kept"));
        V1Job deleted =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("deleted")
                                        .deletionTimestamp(OffsetDateTime.now()));
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

    @Test(expected = KubernetesException.class)
    public void deleteJob_fails() throws ApiException {
        given(deleteJob.execute())
                .willThrow(new ApiException(HttpStatus.FORBIDDEN.value(), "forbidden"));
        wrapper.deleteJob("123");
    }

    @Test
    public void listAllTaskmasterJobsForUser_any() throws ApiException {

        User user = User.builder("123").build();
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        given(listJobs.execute()).willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        verifyListJobs("test-namespace", argument);
        assertThat(argument.getValue(), is("job-type=taskmaster"));
        assertThat(result, is(this.resultList()));
    }

    @Test
    public void listAllTaskmasterJobsForUser_admin() throws ApiException {

        User user = User.builder("123").teskAdmin(true).build();
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        given(listJobs.execute()).willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        verifyListJobs(namespace, argument);
        assertThat(argument.getValue(), is("job-type=taskmaster"));
        assertThat(result, is(this.resultList()));
    }

    @Test
    public void listAllTaskmasterJobsForUser_member() throws ApiException {

        User user =
                User.builder("123")
                        .teskMemberedGroups(StringUtils.commaDelimitedListToSet("TEST"))
                        .build();
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        given(listJobs.execute()).willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        verifyListJobs(namespace, argument);
        assertThat(
                argument.getValue(),
                is(
                        "job-type=taskmaster,creator-group-name in (TEST),"
                                + "creator-user-hash=a665a45920422f9d417e4867efdc4fb8"));
        // only the user's own jobs, also if Kubernetes returned others
        assertThat(
                result.getItems(),
                is(Arrays.asList(job("TEST", "123"), job("XYZ", "123"), job("ABC", "123"))));
    }

    @Test
    public void listAllTaskmasterJobsForUser_groupAdmin() throws ApiException {

        User user =
                User.builder("123")
                        .teskManagedGroups(StringUtils.commaDelimitedListToSet("TEST,XYZ"))
                        .build();
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        given(listJobs.execute()).willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        verifyListJobs(namespace, argument);
        assertThat(argument.getValue(), is("job-type=taskmaster,creator-group-name in (TEST,XYZ)"));
        assertThat(result, is(this.resultList()));
    }

    @Test
    public void listAllTaskmasterJobsForUser_mixed() throws ApiException {

        User user =
                User.builder("123")
                        .teskManagedGroups(StringUtils.commaDelimitedListToSet("TEST"))
                        .teskMemberedGroups(StringUtils.commaDelimitedListToSet("XYZ"))
                        .build();
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        given(listJobs.execute()).willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        verifyListJobs(namespace, argument);
        assertThat(argument.getValue(), is("job-type=taskmaster,creator-group-name in (XYZ,TEST)"));
        assertThat(result, is(filteredResultList()));
    }

    @Test
    public void listSingleTaskExecutorJobs() throws ApiException {
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        wrapper.listSingleTaskExecutorJobs("123");
        verifyListJobs("test-namespace", argument);
        assertThat(argument.getValue(), is("taskmaster-name=123"));
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

    @Test
    public void listAllFilerJobs() throws ApiException {
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        wrapper.listAllFilerJobs();
        verifyListJobs("test-namespace", argument);
        assertThat(argument.getValue(), is("!job-type"));
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

    private static V1Job job(String group, String userId) {
        V1ObjectMeta metadata = new V1ObjectMeta().putLabelsItem(LABEL_GROUPNAME_KEY, group);
        CreatorLabels.put(metadata, userId);
        return new V1Job().metadata(metadata);
    }

    private static V1Job legacyJob(String group, String legacyUserLabel) {
        return new V1Job()
                .metadata(
                        new V1ObjectMeta()
                                .putLabelsItem(LABEL_GROUPNAME_KEY, group)
                                .putLabelsItem(LABEL_LEGACY_USERID_KEY, legacyUserLabel));
    }

    private V1JobList resultList() {
        V1JobList jobList = new V1JobList();
        jobList.setItems(
                Arrays.asList(
                        job("TEST", "123"),
                        job("TEST", "124"),
                        job("XYZ", "123"),
                        job("XYZ", "124"),
                        job("ABC", "123")));
        return jobList;
    }

    private V1JobList filteredResultList() {
        V1JobList jobList = new V1JobList();
        jobList.setItems(
                Arrays.asList(
                        job("TEST", "123"),
                        job("TEST", "124"),
                        job("XYZ", "123"),
                        // this one wasn't filtered in Java, but K8s should not return this one
                        job("ABC", "123")));
        return jobList;
    }

    private V1JobList listJobsReturning(V1Job... jobs) throws ApiException {
        V1JobList jobList = new V1JobList().items(Arrays.asList(jobs));
        given(listJobs.execute()).willReturn(jobList);
        return jobList;
    }

    /** IDs that the old label encoding merged are told apart (issue #119) */
    @Test
    public void listAllTaskmasterJobsForUser_exactCreatorOnly() throws ApiException {
        listJobsReturning(job("TEST", "a@b"), job("TEST", "a_b"));
        User user =
                User.builder("a_b")
                        .teskMemberedGroups(StringUtils.commaDelimitedListToSet("TEST"))
                        .build();
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        assertThat(result.getItems(), is(Arrays.asList(job("TEST", "a_b"))));
    }

    /** Jobs with only the old label belong to nobody, unless the setting is on (issue #119) */
    @Test
    public void listAllTaskmasterJobsForUser_legacyLabels() throws ApiException {
        listJobsReturning(legacyJob("TEST", "a_b"), legacyJob("TEST", "c_d"), job("TEST", "a@b"));
        User user =
                User.builder("a@b")
                        .teskMemberedGroups(StringUtils.commaDelimitedListToSet("TEST"))
                        .build();
        assertThat(
                wrapper.listAllTaskmasterJobsForUser(null, null, user).getItems(),
                is(Arrays.asList(job("TEST", "a@b"))));
        User legacyUser =
                User.builder("a@b")
                        .teskMemberedGroups(StringUtils.commaDelimitedListToSet("TEST"))
                        .legacyUserLabels(true)
                        .build();
        // a new list: the wrapper replaces the items of the one it gets
        listJobsReturning(legacyJob("TEST", "a_b"), legacyJob("TEST", "c_d"), job("TEST", "a@b"));
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        assertThat(
                wrapper.listAllTaskmasterJobsForUser(null, null, legacyUser).getItems(),
                is(Arrays.asList(legacyJob("TEST", "a_b"), job("TEST", "a@b"))));
        Mockito.verify(batchApi, Mockito.times(2)).listNamespacedJob(namespace);
        Mockito.verify(listJobs, Mockito.times(2)).labelSelector(argument.capture());
        assertThat(argument.getValue(), is("job-type=taskmaster,creator-group-name in (TEST)"));
    }

    /** Managers see all jobs of their groups, whoever created them, as before */
    @Test
    public void listAllTaskmasterJobsForUser_managerSeesLegacyJobs() throws ApiException {
        listJobsReturning(legacyJob("TEST", "c_d"), job("TEST", "e@f"));
        User user =
                User.builder("a@b")
                        .teskManagedGroups(StringUtils.commaDelimitedListToSet("TEST"))
                        .build();
        assertThat(
                wrapper.listAllTaskmasterJobsForUser(null, null, user).getItems(),
                is(Arrays.asList(legacyJob("TEST", "c_d"), job("TEST", "e@f"))));
    }

    /**
     * Jobs without labels belong to no group and no user, so the filter leaves them out, also with
     * only the creator annotation and with the legacy-label setting on
     */
    @Test
    public void listAllTaskmasterJobsForUser_jobWithoutLabels() throws ApiException {
        V1ObjectMeta annotatedOnly = new V1ObjectMeta().putAnnotationsItem(ANN_USERID_KEY, "a@b");
        listJobsReturning(
                new V1Job().metadata(new V1ObjectMeta()),
                new V1Job().metadata(annotatedOnly),
                job("TEST", "a@b"));
        User user =
                User.builder("a@b")
                        .teskMemberedGroups(StringUtils.commaDelimitedListToSet("TEST"))
                        .legacyUserLabels(true)
                        .build();
        assertThat(
                wrapper.listAllTaskmasterJobsForUser(null, null, user).getItems(),
                is(Arrays.asList(job("TEST", "a@b"))));
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
        given(listJobs.execute()).willReturn(this.resultList());
        wrapper.listAllTaskmasterJobsForUser("token", 10, User.builder("123").build());
        Mockito.verify(listJobs)._continue("token");
        Mockito.verify(listJobs).limit(10);
    }

    /** Without a page token, a 400 of Kubernetes stays a server error */
    @Test(expected = KubernetesException.class)
    public void listAllTaskmasterJobsForUser_badRequestWithoutPageToken() throws ApiException {
        this.listJobsFails(HttpStatus.BAD_REQUEST.value(), "bad selector");
        wrapper.listAllTaskmasterJobsForUser(null, null, User.builder("123").build());
    }

    /** A 400 of Kubernetes that isn't about the continue token stays a server error */
    @Test(expected = KubernetesException.class)
    public void listAllTaskmasterJobsForUser_otherBadRequestWithPageToken() throws ApiException {
        this.listJobsFails(
                HttpStatus.BAD_REQUEST.value(),
                "{\"kind\":\"Status\",\"message\":\"unable to parse requirement\"}");
        wrapper.listAllTaskmasterJobsForUser("token", null, User.builder("123").build());
    }

    /** A 400 of Kubernetes without a Status to tell the cause stays a server error */
    @Test(expected = KubernetesException.class)
    public void listAllTaskmasterJobsForUser_badRequestWithoutBodyWithPageToken()
            throws ApiException {
        this.listJobsFails(HttpStatus.BAD_REQUEST.value(), null);
        wrapper.listAllTaskmasterJobsForUser("token", null, User.builder("123").build());
    }

    /** Other errors of Kubernetes stay server errors, also with a page token */
    @Test(expected = KubernetesException.class)
    public void listAllTaskmasterJobsForUser_serverErrorWithPageToken() throws ApiException {
        this.listJobsFails(HttpStatus.INTERNAL_SERVER_ERROR.value(), "error");
        wrapper.listAllTaskmasterJobsForUser("token", null, User.builder("123").build());
    }

    /**
     * Lets Kubernetes answer the list request with an error and the response body (a Status object)
     */
    private void listJobsFails(int code, String responseBody) throws ApiException {
        given(listJobs.execute()).willThrow(new ApiException("", code, null, responseBody));
    }
}
