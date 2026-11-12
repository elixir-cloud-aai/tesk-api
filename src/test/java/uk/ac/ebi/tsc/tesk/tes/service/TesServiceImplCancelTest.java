package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.JOB_NAME_FILER_SUF;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_VALUE_EXEC;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_VALUE_TASKM;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_VALUE_CANC;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TESTASK_ID_KEY;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodStatus;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.exception.CancelNotRunningTask;

/** Cancelling a task derives its state from all its jobs, as getting it does. */
class TesServiceImplCancelTest {

    private static final String TASK = "task-1234";

    private KubernetesClientWrapper client;
    private TesServiceImpl service;
    private V1Job taskmaster;
    private V1PodList taskmasterPods;

    @BeforeEach
    void setUp() {
        this.client = mock(KubernetesClientWrapper.class);
        TesKubernetesConverter converter =
                new TesKubernetesConverter(null, null, new ObjectMapper(), null, null);
        this.service = new TesServiceImpl(this.client, converter);
        this.taskmaster =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK)
                                        .putLabelsItem(
                                                LABEL_JOBTYPE_KEY, LABEL_JOBTYPE_VALUE_TASKM))
                        .spec(selector(TASK))
                        .status(new V1JobStatus().active(1));
        V1Pod taskmasterPod =
                new V1Pod()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + "-pod")
                                        .putLabelsItem("job-name", TASK))
                        .status(new V1PodStatus().phase("Running"));
        this.taskmasterPods = new V1PodList().addItemsItem(taskmasterPod);
        when(this.client.readTaskmasterJob(TASK)).thenReturn(this.taskmaster);
        V1Pod executorPod =
                new V1Pod()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + "-ex-00-pod")
                                        .putLabelsItem("job-name", TASK + "-ex-00"))
                        .status(new V1PodStatus().phase("Running"));
        when(this.client.listSingleJobPods(any(V1Job.class)))
                .thenAnswer(
                        invocation -> {
                            V1Job job = invocation.getArgument(0);
                            if (TASK.equals(job.getMetadata().getName())) {
                                return this.taskmasterPods;
                            }
                            if ((TASK + "-ex-00").equals(job.getMetadata().getName())) {
                                return new V1PodList().addItemsItem(executorPod);
                            }
                            return new V1PodList().items(Collections.emptyList());
                        });
        when(this.client.getSingleTaskOutputFilerJob(TASK)).thenReturn(Optional.empty());
        when(this.client.listSingleTaskExecutorJobs(TASK)).thenReturn(new V1JobList());
    }

    private static V1JobSpec selector(String jobName) {
        return new V1JobSpec()
                .selector(new V1LabelSelector().putMatchLabelsItem("job-name", jobName));
    }

    private void executorWithCondition(String type) {
        V1Job executor =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + "-ex-00")
                                        .putLabelsItem(LABEL_JOBTYPE_KEY, LABEL_JOBTYPE_VALUE_EXEC)
                                        .putLabelsItem(LABEL_TESTASK_ID_KEY, TASK))
                        .spec(selector(TASK + "-ex-00"))
                        .status(
                                new V1JobStatus()
                                        .addConditionsItem(
                                                new V1JobCondition().type(type).status("True")));
        when(this.client.listSingleTaskExecutorJobs(TASK))
                .thenReturn(new V1JobList().addItemsItem(executor));
    }

    /**
     * An executor failed, while the taskmaster still runs: the task has ended, and isn't cancelled.
     */
    @Test
    void failedExecutorIsNotCancelled() {
        this.executorWithCondition("FailureTarget");
        assertThatThrownBy(() -> this.service.cancelTask(TASK))
                .isInstanceOf(CancelNotRunningTask.class);
        verify(this.client, never()).labelJobAsCancelled(anyString());
        verify(this.client, never()).cancelJobBeforeStart(anyString());
        verify(this.client, never()).labelPodAsCancelled(anyString());
    }

    /**
     * A running task is cancelled: its taskmaster's job and running pod are labelled, not the
     * executor's pod.
     */
    @Test
    void runningTaskIsCancelled() {
        V1Job executor =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + "-ex-00")
                                        .putLabelsItem(LABEL_JOBTYPE_KEY, LABEL_JOBTYPE_VALUE_EXEC)
                                        .putLabelsItem(LABEL_TESTASK_ID_KEY, TASK))
                        .spec(selector(TASK + "-ex-00"))
                        .status(new V1JobStatus().active(1));
        when(this.client.listSingleTaskExecutorJobs(TASK))
                .thenReturn(new V1JobList().addItemsItem(executor));
        this.service.cancelTask(TASK);
        verify(this.client).labelJobAsCancelled(TASK);
        verify(this.client).labelPodAsCancelled(TASK + "-pod");
        verify(this.client, never()).labelPodAsCancelled(TASK + "-ex-00-pod");
        verify(this.client, never()).cancelJobBeforeStart(anyString());
    }

    /**
     * A task whose taskmaster's pod is pending is cancelled before it starts: the pod is labelled,
     * and the job is labelled and ended, so that the pod is removed (issue #126).
     */
    @Test
    void pendingTaskIsCancelledBeforeStart() {
        this.taskmasterPods.getItems().get(0).getStatus().phase("Pending");
        this.service.cancelTask(TASK);
        verify(this.client).labelPodAsCancelled(TASK + "-pod");
        verify(this.client).cancelJobBeforeStart(TASK);
        verify(this.client, never()).labelJobAsCancelled(anyString());
    }

    /**
     * The job of a task without a running taskmaster is ended first; a pending pod that can't be
     * labelled (e.g. gone in between) doesn't fail the request (issue #126).
     */
    @Test
    void pendingPodThatIsGoneIsSkipped() {
        this.taskmasterPods.getItems().get(0).getStatus().phase("Pending");
        org.mockito.Mockito.doThrow(
                        uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException.fromApiException(
                                new io.kubernetes.client.openapi.ApiException(404, "gone")))
                .when(this.client)
                .labelPodAsCancelled(TASK + "-pod");
        this.service.cancelTask(TASK);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(this.client);
        order.verify(this.client).cancelJobBeforeStart(TASK);
        order.verify(this.client).labelPodAsCancelled(TASK + "-pod");
    }

    /**
     * Without a running taskmaster, only pending pods are labelled: a pod in another phase (e.g.
     * Unknown) isn't, but the job is ended all the same.
     */
    @Test
    void podInOtherPhaseIsNotLabelled() {
        this.taskmasterPods.getItems().get(0).getStatus().phase("Unknown");
        this.service.cancelTask(TASK);
        verify(this.client).cancelJobBeforeStart(TASK);
        verify(this.client, never()).labelPodAsCancelled(anyString());
    }

    /** A taskmaster's pod without a status yet is neither running nor pending. */
    @Test
    void podWithoutStatusIsNotRunning() {
        this.taskmasterPods.getItems().get(0).setStatus(null);
        this.service.cancelTask(TASK);
        verify(this.client).cancelJobBeforeStart(TASK);
        verify(this.client, never()).labelJobAsCancelled(anyString());
        verify(this.client, never()).labelPodAsCancelled(anyString());
    }

    /**
     * A new task, whose taskmaster's job has no pod yet, is cancelled before it starts: the job is
     * labelled and ended, so that a pod that the job controller may still create is removed (issue
     * #126).
     */
    @Test
    void newTaskIsCancelledBeforeStart() {
        this.taskmaster.status(new V1JobStatus());
        this.taskmasterPods.setItems(Collections.emptyList());
        this.service.cancelTask(TASK);
        verify(this.client).cancelJobBeforeStart(TASK);
        verify(this.client, never()).labelJobAsCancelled(anyString());
        verify(this.client, never()).labelPodAsCancelled(anyString());
    }

    private void verifyNothingPatched() {
        verify(this.client, never()).labelJobAsCancelled(anyString());
        verify(this.client, never()).cancelJobBeforeStart(anyString());
        verify(this.client, never()).labelPodAsCancelled(anyString());
    }

    private V1Pod taskmasterPod() {
        return this.taskmasterPods.getItems().get(0);
    }

    /**
     * The taskmaster's job is labelled as cancelled; its pod runs and, if {@code podLabelled}, is
     * labelled, too.
     */
    private void cancelledRunningTaskmaster(boolean podLabelled) {
        this.taskmaster
                .getMetadata()
                .putLabelsItem(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        this.taskmasterPod().getStatus().phase("Running");
        if (podLabelled) {
            this.taskmasterPod()
                    .getMetadata()
                    .putLabelsItem(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        }
    }

    /** The taskmaster's job is labelled as cancelled and failed: its pod has ended. */
    private void cancelledEndedTaskmaster() {
        this.taskmaster
                .getMetadata()
                .putLabelsItem(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        this.taskmaster.status(new V1JobStatus().failed(1));
        this.taskmasterPod().getStatus().phase("Failed");
    }

    /**
     * A task that is canceling (cancelled, a pod still running) is cancelled already: the request
     * succeeds and patches nothing again (issue #144).
     */
    @Test
    void cancelingTaskIsNotPatchedAgain() {
        this.cancelledRunningTaskmaster(true);
        this.service.cancelTask(TASK);
        this.verifyNothingPatched();
    }

    /**
     * A canceling task whose running taskmaster pod lacks the label (e.g. labelling it failed, or
     * Kubernetes restarted the taskmaster) gets it; the job isn't patched again (issue #144).
     */
    @Test
    void cancelingTaskLabelsUnlabelledTaskmasterPod() {
        this.cancelledRunningTaskmaster(false);
        this.service.cancelTask(TASK);
        verify(this.client).labelPodAsCancelled(this.taskmasterPod().getMetadata().getName());
        verify(this.client, never()).labelJobAsCancelled(anyString());
        verify(this.client, never()).cancelJobBeforeStart(anyString());
    }

    /**
     * A canceling task's taskmaster pod that can't be labelled (e.g. gone in between) doesn't fail
     * the request: the job's label keeps the task cancelled (issue #144).
     */
    @Test
    void cancelingTaskSkipsTaskmasterPodThatIsGone() {
        this.cancelledRunningTaskmaster(false);
        org.mockito.Mockito.doThrow(
                        uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException.fromApiException(
                                new io.kubernetes.client.openapi.ApiException(404, "gone")))
                .when(this.client)
                .labelPodAsCancelled(TASK + "-pod");
        this.service.cancelTask(TASK);
        verify(this.client).labelPodAsCancelled(TASK + "-pod");
        verify(this.client, never()).labelJobAsCancelled(anyString());
    }

    /**
     * A canceling task's taskmaster pod that can't be labelled for another reason fails the
     * request: the taskmaster would go on (issue #144).
     */
    @Test
    void cancelingTaskFailsIfTaskmasterPodCantBeLabelled() {
        this.cancelledRunningTaskmaster(false);
        org.mockito.Mockito.doThrow(
                        uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException.fromApiException(
                                new io.kubernetes.client.openapi.ApiException(403, "forbidden")))
                .when(this.client)
                .labelPodAsCancelled(TASK + "-pod");
        assertThatThrownBy(() -> this.service.cancelTask(TASK))
                .isInstanceOf(uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException.class);
    }

    /**
     * A canceling task's pending taskmaster pod (e.g. Kubernetes restarted the taskmaster) gets the
     * label, too: the taskmaster sees it once it starts (issue #144).
     */
    @Test
    void cancelingTaskLabelsPendingTaskmasterPod() {
        this.cancelledRunningTaskmaster(true);
        this.taskmasterPods.addItemsItem(
                new V1Pod()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + "-pod-2")
                                        .putLabelsItem("job-name", TASK))
                        .status(new V1PodStatus().phase("Pending")));
        this.service.cancelTask(TASK);
        verify(this.client).labelPodAsCancelled(TASK + "-pod-2");
        verify(this.client, never()).labelPodAsCancelled(TASK + "-pod");
        verify(this.client, never()).labelJobAsCancelled(anyString());
        verify(this.client, never()).cancelJobBeforeStart(anyString());
    }

    /** A taskmaster pod without a status yet isn't labelled again either (issue #144). */
    @Test
    void cancelingTaskSkipsTaskmasterPodWithoutStatus() {
        this.cancelledRunningTaskmaster(true);
        this.taskmasterPods.addItemsItem(
                new V1Pod()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + "-pod-2")
                                        .putLabelsItem("job-name", TASK)));
        this.service.cancelTask(TASK);
        this.verifyNothingPatched();
    }

    /** The outputs filer's pods are read only for a cancelled task (issue #144). */
    @Test
    void outputFilerPodsAreNotReadForTaskNotCancelled() {
        V1Job filer =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + JOB_NAME_FILER_SUF)
                                        .putLabelsItem("job-name", TASK + JOB_NAME_FILER_SUF))
                        .spec(selector(TASK + JOB_NAME_FILER_SUF))
                        .status(new V1JobStatus().active(1));
        when(this.client.getSingleTaskOutputFilerJob(TASK)).thenReturn(Optional.of(filer));
        this.service.cancelTask(TASK);
        verify(this.client, never()).listSingleJobPods(filer);
        verify(this.client).labelJobAsCancelled(TASK);
    }

    /** A cancelled task whose pods all ended can't be cancelled again (issue #144). */
    @Test
    void canceledTaskIsNotCancelled() {
        this.cancelledEndedTaskmaster();
        assertThatThrownBy(() -> this.service.cancelTask(TASK))
                .isInstanceOf(CancelNotRunningTask.class);
        this.verifyNothingPatched();
    }

    /**
     * The outputs filer's pods are read, too: a cancelled task whose filer still runs is canceling
     * (issue #144).
     */
    @Test
    void runningOutputFilerKeepsTaskCanceling() {
        this.cancelledEndedTaskmaster();
        V1Job filer =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + JOB_NAME_FILER_SUF)
                                        .putLabelsItem("job-name", TASK + JOB_NAME_FILER_SUF))
                        .spec(selector(TASK + JOB_NAME_FILER_SUF))
                        .status(new V1JobStatus());
        when(this.client.getSingleTaskOutputFilerJob(TASK)).thenReturn(Optional.of(filer));
        V1Pod filerPod =
                new V1Pod()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + "-filer-pod")
                                        .putLabelsItem("job-name", TASK + JOB_NAME_FILER_SUF))
                        .status(new V1PodStatus().phase("Running"));
        when(this.client.listSingleJobPods(filer))
                .thenReturn(new V1PodList().addItemsItem(filerPod));
        this.service.cancelTask(TASK);
        this.verifyNothingPatched();
    }

    /** A preempted task has ended and can't be cancelled (issue #144). */
    @Test
    void preemptedTaskIsNotCancelled() {
        this.executorWithCondition("Failed");
        V1Pod evictedPod =
                new V1Pod()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK + "-ex-00-pod")
                                        .putLabelsItem("job-name", TASK + "-ex-00"))
                        .status(
                                new V1PodStatus()
                                        .phase("Failed")
                                        .addConditionsItem(
                                                new V1PodCondition()
                                                        .type("DisruptionTarget")
                                                        .status("True")
                                                        .reason("EvictionByEvictionAPI")));
        when(this.client.listSingleJobPods(any(V1Job.class)))
                .thenAnswer(
                        invocation ->
                                TASK.equals(
                                                invocation
                                                        .<V1Job>getArgument(0)
                                                        .getMetadata()
                                                        .getName())
                                        ? this.taskmasterPods
                                        : new V1PodList().addItemsItem(evictedPod));
        assertThatThrownBy(() -> this.service.cancelTask(TASK))
                .isInstanceOf(CancelNotRunningTask.class);
        this.verifyNothingPatched();
    }
}
