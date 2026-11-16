package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_VALUE_TASKM;

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
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;
import uk.ac.ebi.tsc.tesk.authz.TesAuthz;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageTokenException;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesListTasksResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/** Get, cancel and list with a decision service (tesk.api.authz) */
class TesServiceImplAuthzTest {

    private static final String TASK = "task-1";
    private static final String OWNER = "https://idp|alice";
    private static final User ALICE = User.builder("alice").build();

    private KubernetesClientWrapper client;
    private TesAuthz authz;
    private TesServiceImpl service;

    @BeforeEach
    void setUp() {
        this.client = mock(KubernetesClientWrapper.class);
        this.authz = mock(TesAuthz.class);
        when(this.authz.isDecisionService()).thenReturn(true);
        when(this.authz.ownerId(ALICE)).thenReturn(Optional.of(OWNER));
        TesKubernetesConverter converter =
                new TesKubernetesConverter(null, null, new ObjectMapper(), null, null);
        this.service = new TesServiceImpl(this.client, converter, this.authz);
        when(this.client.listSingleTaskExecutorJobs(any())).thenReturn(new V1JobList());
        when(this.client.listSingleJobPods(any(V1Job.class))).thenReturn(new V1PodList());
        when(this.client.getSingleTaskOutputFilerJob(any())).thenReturn(Optional.empty());
    }

    /** A running taskmaster job of a task with tags, by the creator, with the given owner */
    private static V1Job job(String name, String creator, String owner) {
        V1ObjectMeta metadata =
                new V1ObjectMeta()
                        .name(name)
                        .putLabelsItem(LABEL_JOBTYPE_KEY, LABEL_JOBTYPE_VALUE_TASKM)
                        .putAnnotationsItem("json-input", "{\"tags\": {\"project\": \"demo\"}}");
        if (creator != null) {
            CreatorLabels.put(metadata, creator);
        }
        if (owner != null) {
            metadata.putAnnotationsItem("owner-id", owner);
        }
        return new V1Job()
                .metadata(metadata)
                .spec(
                        new V1JobSpec()
                                .selector(
                                        new V1LabelSelector().putMatchLabelsItem("job-name", name)))
                .status(new V1JobStatus().active(1));
    }

    private void exists(V1Job job) {
        when(this.client.readTaskmasterJob(job.getMetadata().getName())).thenReturn(job);
    }

    private void missing(String name) {
        when(this.client.readTaskmasterJob(name)).thenThrow(new TaskNotFoundException(name));
    }

    private TesAuthz.TaskMeta readMeta() {
        ArgumentCaptor<TesAuthz.TaskMeta> meta = ArgumentCaptor.forClass(TesAuthz.TaskMeta.class);
        verify(this.authz).mayRead(eq(ALICE), eq(TASK), meta.capture(), eq(TaskView.BASIC));
        return meta.getValue();
    }

    @Test
    void getTask_allowed() {
        exists(job(TASK, "alice", OWNER));
        when(this.authz.mayRead(eq(ALICE), eq(TASK), any(), eq(TaskView.BASIC))).thenReturn(true);

        TesTask task = this.service.getTask(TASK, TaskView.BASIC, ALICE);

        assertThat(task.getId()).isEqualTo(TASK);
        TesAuthz.TaskMeta meta = readMeta();
        assertThat(meta.getOwner()).contains(OWNER);
        assertThat(meta.getState()).isEqualTo("INITIALIZING");
        assertThat(meta.getTags()).containsEntry("project", "demo");
    }

    @Test
    void getTask_denied() {
        exists(job(TASK, "alice", OWNER));

        assertThatThrownBy(() -> this.service.getTask(TASK, TaskView.BASIC, ALICE))
                .isInstanceOf(TaskNotFoundException.class);
    }

    /** Missing tasks: the decision service is still asked (without properties), then 404 */
    @Test
    void getTask_missing() {
        missing(TASK);
        when(this.authz.mayRead(eq(ALICE), eq(TASK), isNull(), eq(TaskView.BASIC)))
                .thenReturn(true);

        assertThatThrownBy(() -> this.service.getTask(TASK, TaskView.BASIC, ALICE))
                .isInstanceOf(TaskNotFoundException.class);
        verify(this.authz).mayRead(eq(ALICE), eq(TASK), isNull(), eq(TaskView.BASIC));
    }

    /** Tasks created before owners were stamped: owner derived from the creator, if possible */
    @Test
    void getTask_ownerFromCreator() {
        exists(job(TASK, "alice", null));
        when(this.authz.ownerOfCreator("alice")).thenReturn(Optional.of(OWNER));

        assertThatThrownBy(() -> this.service.getTask(TASK, TaskView.BASIC, ALICE))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(readMeta().getOwner()).contains(OWNER);
    }

    /** Legacy tasks (only the old label) have no exact owner */
    @Test
    void getTask_legacyTaskHasNoOwner() {
        V1Job job = job(TASK, null, null);
        job.getMetadata().setAnnotations(null);
        exists(job);
        when(this.authz.ownerOfCreator(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.getTask(TASK, TaskView.BASIC, ALICE))
                .isInstanceOf(TaskNotFoundException.class);
        TesAuthz.TaskMeta meta = readMeta();
        assertThat(meta.getOwner()).isEmpty();
        assertThat(meta.getTags()).isEmpty();
    }

    @Test
    void cancelTask_allowed() {
        V1Job job = job(TASK, "alice", OWNER);
        exists(job);
        when(this.client.listSingleJobPods(job))
                .thenReturn(
                        new V1PodList()
                                .addItemsItem(
                                        new V1Pod()
                                                .metadata(
                                                        new V1ObjectMeta()
                                                                .name(TASK + "-pod")
                                                                .putLabelsItem("job-name", TASK))
                                                .status(new V1PodStatus().phase("Running"))));

        this.service.cancelTask(TASK, ALICE);

        verify(this.authz).checkCancel(eq(ALICE), eq(TASK), any(TesAuthz.TaskMeta.class));
        verify(this.client).labelJobAsCancelled(TASK);
        verify(this.client).labelPodAsCancelled(TASK + "-pod");
    }

    @Test
    void cancelTask_deniedOrMissing() {
        missing(TASK);
        doThrow(new TaskNotFoundException(TASK))
                .when(this.authz)
                .checkCancel(eq(ALICE), eq(TASK), isNull());

        assertThatThrownBy(() -> this.service.cancelTask(TASK, ALICE))
                .isInstanceOf(TaskNotFoundException.class);
        verify(this.client, never()).labelJobAsCancelled(any());
    }

    private void listed(String continueToken, V1Job... jobs) {
        V1JobList list =
                new V1JobList()
                        .metadata(new V1ListMeta()._continue(continueToken))
                        .items(Arrays.asList(jobs));
        when(this.client.listTaskmasterJobsOfCreator(any(), any(), any())).thenReturn(list);
        when(this.client.listAllTaskExecutorJobs()).thenReturn(new V1JobList());
        when(this.client.listFilerJobsOfTasks(anyList())).thenReturn(new V1JobList());
        when(this.client.listAllJobPods()).thenReturn(new V1PodList());
        when(this.client.listUnlabelledFilerJobs()).thenReturn(new V1JobList());
    }

    @Test
    void listTasks_all() {
        when(this.authz.listsAll(ALICE, TaskView.MINIMAL)).thenReturn(true);
        listed("next", job("a", "bob", "https://idp|bob"), job("b", "alice", OWNER));

        TesListTasksResponse response =
                this.service.listTasks(TaskFilter.NONE, 2L, "all:prev", TaskView.MINIMAL, ALICE);

        verify(this.client).listTaskmasterJobsOfCreator("prev", 2, null);
        assertThat(response.getTasks()).hasSize(2);
        assertThat(response.getNextPageToken()).isEqualTo("all:next");
    }

    /** Own scope: selected by the creator's hash label, kept only with the exact owner */
    @Test
    void listTasks_own() {
        when(this.authz.listsAll(ALICE, TaskView.MINIMAL)).thenReturn(false);
        listed(null, job("a", "alice", "https://other|alice"), job("b", "alice", OWNER));

        TesListTasksResponse response =
                this.service.listTasks(TaskFilter.NONE, null, null, TaskView.MINIMAL, ALICE);

        verify(this.client).listTaskmasterJobsOfCreator(null, null, "alice");
        assertThat(response.getTasks()).extracting(TesTask::getId).containsExactly("b");
        assertThat(response.getNextPageToken()).isNull();
    }

    /** Case 13b: the scope changed between pages */
    @Test
    void listTasks_tokenOfOtherScope() {
        when(this.authz.listsAll(ALICE, TaskView.MINIMAL)).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                this.service.listTasks(
                                        TaskFilter.NONE, 2L, "all:x", TaskView.MINIMAL, ALICE))
                .isInstanceOf(InvalidPageTokenException.class);
        verify(this.client, never()).listTaskmasterJobsOfCreator(any(), any(), any());
    }

    @Test
    void listTasks_denied() {
        when(this.authz.listsAll(ALICE, TaskView.MINIMAL))
                .thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(
                        () ->
                                this.service.listTasks(
                                        TaskFilter.NONE, 2L, null, TaskView.MINIMAL, ALICE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listTasks_ownWithoutOwnerId() {
        when(this.authz.listsAll(ALICE, TaskView.MINIMAL)).thenReturn(false);
        when(this.authz.ownerId(ALICE)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                this.service.listTasks(
                                        TaskFilter.NONE, 2L, null, TaskView.MINIMAL, ALICE))
                .isInstanceOf(IllegalStateException.class);
    }
}
