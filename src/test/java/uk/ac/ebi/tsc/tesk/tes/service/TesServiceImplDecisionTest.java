package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.Collections;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageTokenException;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesListTasksResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.CancelDecision;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.ListScope;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.Operation;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.TaskFacts;

/**
 * Get, list and cancel when a decision service decides them (switched on): asked for missing tasks
 * too, foreign tasks served when it allows (widen), and the list scope applied by TESK
 */
class TesServiceImplDecisionTest {

    private static final String TASK = "task-1234";

    private static final String OWNER = "https://idp.example|owner";

    /** A caller who isn't the task's owner */
    private static final User OTHER = User.builder("https://idp.example|other").build();

    private KubernetesClientWrapper client;
    private TaskAccess access;
    private TesServiceImpl service;
    private V1Job taskmaster;

    @BeforeEach
    void setUp() {
        this.client = mock(KubernetesClientWrapper.class);
        this.access = mock(TaskAccess.class);
        when(this.access.decides(any())).thenReturn(true);
        TesKubernetesConverter converter =
                new TesKubernetesConverter(null, null, new ObjectMapper(), null, null);
        this.service = new TesServiceImpl(this.client, converter, this.access);
        this.taskmaster =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK)
                                        .putLabelsItem(
                                                LABEL_JOBTYPE_KEY, LABEL_JOBTYPE_VALUE_TASKM))
                        .spec(
                                new V1JobSpec()
                                        .selector(
                                                new V1LabelSelector()
                                                        .putMatchLabelsItem("job-name", TASK)))
                        .status(new V1JobStatus().active(1));
        CreatorLabels.put(this.taskmaster.getMetadata(), OWNER);
        V1Pod pod =
                new V1Pod()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + "-pod")
                                        .putLabelsItem("job-name", TASK))
                        .status(new V1PodStatus().phase("Running"));
        when(this.client.readTaskmasterJob(TASK)).thenReturn(this.taskmaster);
        when(this.client.listSingleJobPods(any(V1Job.class)))
                .thenAnswer(invocation -> {
                    V1Job job = invocation.getArgument(0);
                    return TASK.equals(job.getMetadata().getName())
                            ? new V1PodList().addItemsItem(pod)
                            : new V1PodList().items(Collections.emptyList());
                });
        when(this.client.getSingleTaskOutputFilerJob(TASK)).thenReturn(Optional.empty());
        when(this.client.listSingleTaskExecutorJobs(TASK)).thenReturn(new V1JobList());
        when(this.client.readTaskmasterJob("missing"))
                .thenThrow(new TaskNotFoundException("missing"));
    }

    /** The facts that the decision service got for the task */
    private TaskFacts factsOfGet() {
        ArgumentCaptor<TaskFacts> facts = ArgumentCaptor.forClass(TaskFacts.class);
        verify(this.access).mayGet(eq(OTHER), eq(TASK), facts.capture(), eq(TaskView.MINIMAL));
        return facts.getValue();
    }

    @Test
    void get_allowed_servesAForeignTask() {
        when(this.access.mayGet(any(), any(), any(), any())).thenReturn(true);

        assertThat(this.service.getTask(TASK, TaskView.MINIMAL, OTHER).getId()).isEqualTo(TASK);

        TaskFacts facts = factsOfGet();
        assertThat(facts.owner()).isEqualTo(OWNER);
        assertThat(facts.state()).isEqualTo(TesState.INITIALIZING);
        assertThat(facts.tags()).isEmpty();
    }

    @Test
    void get_denied_isNotFound() {
        assertThatThrownBy(() -> this.service.getTask(TASK, TaskView.MINIMAL, OTHER))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(factsOfGet()).isNotNull();
    }

    /** Missing tasks are asked about too (no existence oracle), and are 404 even if allowed */
    @Test
    void get_missing_askedAndNotFound() {
        when(this.access.mayGet(any(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> this.service.getTask("missing", TaskView.BASIC, OTHER))
                .isInstanceOf(TaskNotFoundException.class);

        verify(this.access).mayGet(eq(OTHER), eq("missing"), isNull(), eq(TaskView.BASIC));
    }

    /** A task without an exact owner counts as missing */
    @Test
    void get_withoutExactOwner_askedAsMissing() {
        when(this.access.mayGet(any(), any(), any(), any())).thenReturn(true);
        this.taskmaster.getMetadata().getAnnotations().clear();

        assertThatThrownBy(() -> this.service.getTask(TASK, TaskView.MINIMAL, OTHER))
                .isInstanceOf(TaskNotFoundException.class);

        verify(this.access).mayGet(eq(OTHER), eq(TASK), isNull(), eq(TaskView.MINIMAL));
        verify(this.client, never()).listSingleTaskExecutorJobs(TASK);
    }

    @Test
    void cancel_allowed_cancelsAForeignTask() {
        when(this.access.mayCancel(any(), any(), any())).thenReturn(CancelDecision.ALLOWED);

        this.service.cancelTask(TASK, OTHER);

        verify(this.client).labelJobAsCancelled(TASK);
        verify(this.client).labelPodAsCancelled(TASK + "-pod");
    }

    @Test
    void cancel_forbidden_is403() {
        when(this.access.mayCancel(any(), any(), any())).thenReturn(CancelDecision.FORBIDDEN);

        assertThatThrownBy(() -> this.service.cancelTask(TASK, OTHER))
                .isInstanceOf(AccessDeniedException.class);
        verify(this.client, never()).labelJobAsCancelled(any());
    }

    @Test
    void cancel_notFound_is404() {
        when(this.access.mayCancel(any(), any(), any())).thenReturn(CancelDecision.NOT_FOUND);

        assertThatThrownBy(() -> this.service.cancelTask(TASK, OTHER))
                .isInstanceOf(TaskNotFoundException.class);
        verify(this.client, never()).labelJobAsCancelled(any());
    }

    @Test
    void cancel_missing_allowed_is404() {
        when(this.access.mayCancel(any(), any(), any())).thenReturn(CancelDecision.ALLOWED);

        assertThatThrownBy(() -> this.service.cancelTask("missing", OTHER))
                .isInstanceOf(TaskNotFoundException.class);
        verify(this.access).mayCancel(OTHER, "missing", null);
    }

    @Test
    void list_none_readsNothing() {
        when(this.access.listScope(OTHER, TaskView.MINIMAL)).thenReturn(ListScope.NONE);

        TesListTasksResponse response =
                this.service.listTasks(TaskFilter.NONE, 10L, "token", TaskView.MINIMAL, OTHER);

        assertThat(response.getTasks()).isEmpty();
        assertThat(response.getNextPageToken()).isNull();
        verifyNoInteractions(this.client);
    }

    @Test
    void list_all_withoutOwnerSelector_andMarkedTokens() {
        when(this.access.listScope(OTHER, TaskView.MINIMAL)).thenReturn(ListScope.ALL);
        when(this.client.listAllOwnedTaskmasterJobs("abc", 10))
                .thenReturn(new V1JobList().metadata(new V1ListMeta()._continue("def")));

        TesListTasksResponse response =
                this.service.listTasks(TaskFilter.NONE, 10L, "all.abc", TaskView.MINIMAL, OTHER);

        assertThat(response.getNextPageToken()).isEqualTo("all.def");
        verify(this.client, never()).listAllTaskmasterJobsForUser(any(), any(), any());
    }

    @Test
    void list_all_refusesATokenOfOwnPages() {
        when(this.access.listScope(OTHER, TaskView.MINIMAL)).thenReturn(ListScope.ALL);

        assertThatThrownBy(() ->
                        this.service.listTasks(
                                TaskFilter.NONE, 10L, "abc", TaskView.MINIMAL, OTHER))
                .isInstanceOf(InvalidPageTokenException.class);
        verifyNoInteractions(this.client);
    }

    @Test
    void list_own_refusesATokenOfAllPages() {
        when(this.access.listScope(OTHER, TaskView.MINIMAL)).thenReturn(ListScope.OWN);

        assertThatThrownBy(() ->
                        this.service.listTasks(
                                TaskFilter.NONE, 10L, "all.abc", TaskView.MINIMAL, OTHER))
                .isInstanceOf(InvalidPageTokenException.class);
        verifyNoInteractions(this.client);
    }

    @Test
    void list_own_withOwnerSelector() {
        when(this.access.listScope(OTHER, TaskView.MINIMAL)).thenReturn(ListScope.OWN);
        when(this.client.listAllTaskmasterJobsForUser("abc", null, OTHER))
                .thenReturn(new V1JobList().metadata(new V1ListMeta()._continue("def")));

        TesListTasksResponse response =
                this.service.listTasks(TaskFilter.NONE, null, "abc", TaskView.MINIMAL, OTHER);

        assertThat(response.getNextPageToken()).isEqualTo("def");
    }

    /** Not switched on: no decision is asked for, as with the owner rule */
    @Test
    void notSwitchedOn_asksNothing() {
        when(this.access.decides(Operation.LIST)).thenReturn(false);
        when(this.client.listAllTaskmasterJobsForUser(null, null, OTHER))
                .thenReturn(new V1JobList().metadata(new V1ListMeta()));

        this.service.listTasks(TaskFilter.NONE, null, null, TaskView.MINIMAL, OTHER);

        verify(this.access, never()).listScope(any(), any());
    }

    /** Without a decision service (noauth), the owner rule decides */
    @Test
    @SuppressWarnings("unchecked")
    void withoutTaskAccess_theOwnerRule() {
        ObjectProvider<TaskAccess> none = mock(ObjectProvider.class);
        when(none.getIfAvailable(any()))
                .thenAnswer(invocation -> ((Supplier<TaskAccess>) invocation.getArgument(0)).get());
        TesServiceImpl ownerOnly =
                new TesServiceImpl(
                        this.client,
                        new TesKubernetesConverter(null, null, new ObjectMapper(), null, null),
                        none);

        assertThatThrownBy(() -> ownerOnly.getTask(TASK, TaskView.MINIMAL, OTHER))
                .isInstanceOf(TaskNotFoundException.class);
        verify(this.client, never()).listSingleTaskExecutorJobs(TASK);
    }
}
