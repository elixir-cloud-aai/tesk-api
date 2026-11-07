package uk.ac.ebi.tsc.tesk.k8s.service;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_GROUPNAME_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_USERID_KEY;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import java.util.Arrays;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;
import uk.ac.ebi.tsc.tesk.config.security.User;
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

    @Before
    public void setUp() {

        this.wrapper = new KubernetesClientWrapper(batchApi, batchApi, coreApi, coreApi, namespace);
    }

    /** A ConfigMap is checked by creating it in a dry run */
    @Test
    public void checkConfigMap() throws ApiException {
        V1ConfigMap map = new V1ConfigMap().metadata(new V1ObjectMeta().name("123"));
        wrapper.checkConfigMap(map);
        Mockito.verify(coreApi)
                .createNamespacedConfigMap(
                        eq("test-namespace"), same(map), isNull(), eq("All"), isNull());
    }

    @Test(expected = KubernetesException.class)
    public void checkConfigMap_refused() throws ApiException {
        given(coreApi.createNamespacedConfigMap(any(), any(), any(), any(), any()))
                .willThrow(new ApiException(422, "too large"));
        wrapper.checkConfigMap(new V1ConfigMap());
    }

    /**
     * An admission webhook that doesn't support dry runs makes Kubernetes refuse every dry run
     * (400): the check is skipped, so that tasks can still be created
     */
    @Test
    public void checkConfigMap_webhookWithoutDryRun() throws ApiException {
        given(coreApi.createNamespacedConfigMap(any(), any(), any(), any(), any()))
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
        given(coreApi.createNamespacedConfigMap(any(), any(), any(), any(), any()))
                .willThrow(new ApiException("", 400, null, null));
        wrapper.checkConfigMap(new V1ConfigMap());
    }

    /** Other 400s of the dry run aren't skipped */
    @Test(expected = KubernetesException.class)
    public void checkConfigMap_otherBadRequest() throws ApiException {
        given(coreApi.createNamespacedConfigMap(any(), any(), any(), any(), any()))
                .willThrow(new ApiException("", 400, null, "{\"message\":\"invalid\"}"));
        wrapper.checkConfigMap(new V1ConfigMap());
    }

    @Test
    public void createJob() throws ApiException {
        V1Job inJob = new V1Job().metadata(new V1ObjectMeta().name("test job"));
        V1Job outJob =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("test job")
                                        .creationTimestamp(new DateTime()));
        given(batchApi.createNamespacedJob("test-namespace", inJob, null, null, null))
                .willReturn(outJob);
        V1Job result = wrapper.createJob(inJob);
        assertThat(result, is(outJob));
    }

    @Test
    public void readTaskmasterJob() throws ApiException {
        V1Job job =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("test job")
                                        .putLabelsItem("job-type", "taskmaster"));
        given(batchApi.readNamespacedJob("123", "test-namespace", null, null, null))
                .willReturn(job);
        V1Job result = wrapper.readTaskmasterJob("123");
        assertThat(result, is(job));
    }

    @Test(expected = TaskNotFoundException.class)
    public void readTaskmasterJob_wrongType() throws Exception {
        V1Job job =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("test job")
                                        .putLabelsItem("job-type", "executor"));
        given(batchApi.readNamespacedJob("123", "test-namespace", null, null, null))
                .willReturn(job);
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
        given(batchApi.readNamespacedJob("123", "test-namespace", null, null, null))
                .willReturn(job);
        wrapper.readTaskmasterJob("123");
    }

    @Test(expected = TaskNotFoundException.class)
    public void readTaskmasterJob_noJob() throws Exception {
        given(batchApi.readNamespacedJob("123", "test-namespace", null, null, null))
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
                                        .deletionTimestamp(new DateTime())
                                        .putLabelsItem("job-type", "taskmaster"));
        given(batchApi.readNamespacedJob("123", "test-namespace", null, null, null))
                .willReturn(job);
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
                                        .deletionTimestamp(new DateTime()));
        given(
                        batchApi.listNamespacedJob(
                                any(), isNull(), isNull(), isNull(), isNull(), any(), isNull(),
                                isNull(), isNull(), isNull(), isNull()))
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
        Mockito.verify(batchApi)
                .deleteNamespacedJob(
                        eq("123"),
                        eq("test-namespace"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq("Background"),
                        isNull());
    }

    @Test(expected = KubernetesException.class)
    public void deleteJob_fails() throws ApiException {
        given(batchApi.deleteNamespacedJob(any(), any(), any(), any(), any(), any(), any(), any()))
                .willThrow(new ApiException(HttpStatus.FORBIDDEN.value(), "forbidden"));
        wrapper.deleteJob("123");
    }

    @Test
    public void listAllTaskmasterJobsForUser_any() throws ApiException {

        User user = User.builder("123").build();
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        given(
                        batchApi.listNamespacedJob(
                                any(), isNull(), isNull(), isNull(), isNull(), any(), isNull(),
                                isNull(), isNull(), isNull(), isNull()))
                .willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        Mockito.verify(batchApi)
                .listNamespacedJob(
                        eq("test-namespace"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argument.capture(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
        assertThat(argument.getValue(), is("job-type=taskmaster"));
        assertThat(result, is(this.resultList()));
    }

    @Test
    public void listAllTaskmasterJobsForUser_admin() throws ApiException {

        User user = User.builder("123").teskAdmin(true).build();
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        given(
                        batchApi.listNamespacedJob(
                                any(), isNull(), isNull(), isNull(), isNull(), any(), isNull(),
                                isNull(), isNull(), isNull(), isNull()))
                .willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        Mockito.verify(batchApi)
                .listNamespacedJob(
                        eq(namespace),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argument.capture(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
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
        given(
                        batchApi.listNamespacedJob(
                                any(), isNull(), isNull(), isNull(), isNull(), any(), isNull(),
                                isNull(), isNull(), isNull(), isNull()))
                .willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        Mockito.verify(batchApi)
                .listNamespacedJob(
                        eq(namespace),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argument.capture(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
        assertThat(
                argument.getValue(),
                is("job-type=taskmaster,creator-group-name in (TEST),creator-user-id=123"));
        assertThat(result, is(this.resultList()));
    }

    @Test
    public void listAllTaskmasterJobsForUser_groupAdmin() throws ApiException {

        User user =
                User.builder("123")
                        .teskManagedGroups(StringUtils.commaDelimitedListToSet("TEST,XYZ"))
                        .build();
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        given(
                        batchApi.listNamespacedJob(
                                any(), isNull(), isNull(), isNull(), isNull(), any(), isNull(),
                                isNull(), isNull(), isNull(), isNull()))
                .willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        Mockito.verify(batchApi)
                .listNamespacedJob(
                        eq(namespace),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argument.capture(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
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
        given(
                        batchApi.listNamespacedJob(
                                any(), isNull(), isNull(), isNull(), isNull(), any(), isNull(),
                                isNull(), isNull(), isNull(), isNull()))
                .willReturn(this.resultList());
        V1JobList result = wrapper.listAllTaskmasterJobsForUser(null, null, user);
        Mockito.verify(batchApi)
                .listNamespacedJob(
                        eq(namespace),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argument.capture(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
        assertThat(argument.getValue(), is("job-type=taskmaster,creator-group-name in (XYZ,TEST)"));
        assertThat(result, is(filteredResultList()));
    }

    @Test
    public void listSingleTaskExecutorJobs() throws ApiException {
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        wrapper.listSingleTaskExecutorJobs("123");
        Mockito.verify(batchApi)
                .listNamespacedJob(
                        eq("test-namespace"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argument.capture(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
        assertThat(argument.getValue(), is("taskmaster-name=123"));
    }

    @Test
    public void getSingleTaskOutputFilerJob() throws ApiException {
        V1Job job = new V1Job().metadata(new V1ObjectMeta().name("test job"));
        given(batchApi.readNamespacedJob("123-outputs-filer", "test-namespace", null, null, null))
                .willReturn(job);
        Optional<V1Job> result = wrapper.getSingleTaskOutputFilerJob("123");
        assertThat(result, is(Optional.of(job)));
    }

    @Test
    public void getSingleTaskOutputFilerJob_noJob() throws ApiException {
        given(batchApi.readNamespacedJob("123-outputs-filer", "test-namespace", null, null, null))
                .willThrow(new ApiException(HttpStatus.NOT_FOUND.value(), "No Job"));
        Optional<V1Job> result = wrapper.getSingleTaskOutputFilerJob("123");
        assertThat(result, is(Optional.empty()));
    }

    @Test
    public void listAllTaskExecutorJobs() throws ApiException {
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        wrapper.listAllTaskExecutorJobs();
        Mockito.verify(batchApi)
                .listNamespacedJob(
                        eq("test-namespace"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argument.capture(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
        assertThat(argument.getValue(), is("job-type=executor"));
    }

    @Test
    public void listAllFilerJobs() throws ApiException {
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        wrapper.listAllFilerJobs();
        Mockito.verify(batchApi)
                .listNamespacedJob(
                        eq("test-namespace"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argument.capture(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
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
        Mockito.verify(coreApi)
                .listNamespacedPod(
                        eq("test-namespace"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argument.capture(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
        assertThat(argument.getValue(), is("label1=value1,labelA=valueB"));
    }

    @Test
    public void listAllJobPods() throws ApiException {
        wrapper.listAllJobPods();
        Mockito.verify(coreApi)
                .listNamespacedPod(
                        eq("test-namespace"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq("job-name"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
    }

    private V1JobList resultList() {
        V1JobList jobList = new V1JobList();
        jobList.setItems(
                Arrays.asList(
                        new V1Job()
                                .metadata(
                                        new V1ObjectMeta()
                                                .putLabelsItem(LABEL_GROUPNAME_KEY, "TEST")
                                                .putLabelsItem(LABEL_USERID_KEY, "123")),
                        new V1Job()
                                .metadata(
                                        new V1ObjectMeta()
                                                .putLabelsItem(LABEL_GROUPNAME_KEY, "TEST")
                                                .putLabelsItem(LABEL_USERID_KEY, "124")),
                        new V1Job()
                                .metadata(
                                        new V1ObjectMeta()
                                                .putLabelsItem(LABEL_GROUPNAME_KEY, "XYZ")
                                                .putLabelsItem(LABEL_USERID_KEY, "123")),
                        new V1Job()
                                .metadata(
                                        new V1ObjectMeta()
                                                .putLabelsItem(LABEL_GROUPNAME_KEY, "XYZ")
                                                .putLabelsItem(LABEL_USERID_KEY, "124")),
                        new V1Job()
                                .metadata(
                                        new V1ObjectMeta()
                                                .putLabelsItem(LABEL_GROUPNAME_KEY, "ABC")
                                                .putLabelsItem(LABEL_USERID_KEY, "123"))));
        return jobList;
    }

    private V1JobList filteredResultList() {
        V1JobList jobList = new V1JobList();
        jobList.setItems(
                Arrays.asList(
                        new V1Job()
                                .metadata(
                                        new V1ObjectMeta()
                                                .putLabelsItem(LABEL_GROUPNAME_KEY, "TEST")
                                                .putLabelsItem(LABEL_USERID_KEY, "123")),
                        new V1Job()
                                .metadata(
                                        new V1ObjectMeta()
                                                .putLabelsItem(LABEL_GROUPNAME_KEY, "TEST")
                                                .putLabelsItem(LABEL_USERID_KEY, "124")),
                        new V1Job()
                                .metadata(
                                        new V1ObjectMeta()
                                                .putLabelsItem(LABEL_GROUPNAME_KEY, "XYZ")
                                                .putLabelsItem(LABEL_USERID_KEY, "123")),
                        // this one wan't br filtered in Java, but K8s should not return this one
                        new V1Job()
                                .metadata(
                                        new V1ObjectMeta()
                                                .putLabelsItem(LABEL_GROUPNAME_KEY, "ABC")
                                                .putLabelsItem(LABEL_USERID_KEY, "123"))));
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
                "secret-token",
                code,
                "{\"kind\":\"Status\",\"message\":\"continue key is not valid: secret-token\"}");
        try {
            wrapper.listAllTaskmasterJobsForUser("secret-token", null, User.builder("123").build());
            fail("InvalidPageTokenException expected");
        } catch (InvalidPageTokenException e) {
            assertTrue(e.getMessage().contains("page_token"));
            assertFalse(e.getMessage().contains("secret-token"));
        }
    }

    /** Without a page token, a 400 of Kubernetes stays a server error */
    @Test(expected = KubernetesException.class)
    public void listAllTaskmasterJobsForUser_badRequestWithoutPageToken() throws ApiException {
        this.listJobsFails(null, HttpStatus.BAD_REQUEST.value(), "bad selector");
        wrapper.listAllTaskmasterJobsForUser(null, null, User.builder("123").build());
    }

    /** A 400 of Kubernetes that isn't about the continue token stays a server error */
    @Test(expected = KubernetesException.class)
    public void listAllTaskmasterJobsForUser_otherBadRequestWithPageToken() throws ApiException {
        this.listJobsFails(
                "token",
                HttpStatus.BAD_REQUEST.value(),
                "{\"kind\":\"Status\",\"message\":\"unable to parse requirement\"}");
        wrapper.listAllTaskmasterJobsForUser("token", null, User.builder("123").build());
    }

    /** A 400 of Kubernetes without a Status to tell the cause stays a server error */
    @Test(expected = KubernetesException.class)
    public void listAllTaskmasterJobsForUser_badRequestWithoutBodyWithPageToken()
            throws ApiException {
        this.listJobsFails("token", HttpStatus.BAD_REQUEST.value(), null);
        wrapper.listAllTaskmasterJobsForUser("token", null, User.builder("123").build());
    }

    /** Other errors of Kubernetes stay server errors, also with a page token */
    @Test(expected = KubernetesException.class)
    public void listAllTaskmasterJobsForUser_serverErrorWithPageToken() throws ApiException {
        this.listJobsFails("token", HttpStatus.INTERNAL_SERVER_ERROR.value(), "error");
        wrapper.listAllTaskmasterJobsForUser("token", null, User.builder("123").build());
    }

    /**
     * Lets Kubernetes answer the list request with the page token (or none) with an error and the
     * response body (a Status object)
     */
    private void listJobsFails(String pageToken, int code, String responseBody)
            throws ApiException {
        given(
                        batchApi.listNamespacedJob(
                                any(),
                                isNull(),
                                isNull(),
                                pageToken == null ? isNull() : eq(pageToken),
                                isNull(),
                                any(),
                                isNull(),
                                isNull(),
                                isNull(),
                                isNull(),
                                isNull()))
                .willThrow(new ApiException("", code, null, responseBody));
    }
}
