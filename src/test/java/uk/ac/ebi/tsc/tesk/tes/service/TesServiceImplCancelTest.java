package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_VALUE_EXEC;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_VALUE_TASKM;
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
}
