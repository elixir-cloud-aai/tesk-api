package uk.ac.ebi.tsc.tesk.k8s.convert;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_VALUE_CANC;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1Pod;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import uk.ac.ebi.tsc.tesk.k8s.constant.K8sConstants;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Job;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Task;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;

/**
 * Derives a TES task's state from the Kubernetes jobs of the task: the taskmaster's job, the
 * executors' jobs and the outputs filer's job. The rules, in the order in which they apply, are
 * documented in {@code docs/index.md} ("Getting tasks"); {@code TaskStateResolverTest} holds them
 * as a decision table.
 */
public final class TaskStateResolver {

    /**
     * Job conditions that mark a failed job, when their status is "True"; Kubernetes 1.31+ sets
     * FailureTarget before Failed.
     */
    private static final Set<String> FAILED_CONDITIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("Failed", "FailureTarget")));

    private TaskStateResolver() {}

    /**
     * Derives the state of a task.
     *
     * @param task the task's jobs and their pods
     * @param expectedExecutors supplies the number of executors of the task, or {@code null} if
     *     unknown (then the executor jobs that exist count as all executors); only called once the
     *     taskmaster's job completed
     * @return the TES state of the task
     */
    public static TesState resolve(Task task, Supplier<Integer> expectedExecutors) {
        Job taskmaster = task.getTaskmaster();
        V1Job taskmasterJob = taskmaster.getJob();
        if (isCancelled(taskmasterJob)) {
            return TesState.CANCELED;
        }

        boolean taskmasterCompleted = hasSucceeded(taskmasterJob);
        // Only by its conditions: between retries, the taskmaster's job has
        // failed pods and none active (such a job gives SYSTEM_ERROR below,
        // as before, but doesn't end the task's other jobs)
        boolean taskmasterFailed = !taskmasterCompleted && hasFailedCondition(taskmasterJob);
        boolean taskmasterEnded = taskmasterCompleted || taskmasterFailed;

        List<Job> executors = task.getExecutors();
        if (executors.stream()
                .anyMatch(executor -> hasFailed(executor.getJob(), taskmasterEnded))) {
            return TesState.EXECUTOR_ERROR;
        }
        if (taskmasterFailed) {
            return TesState.SYSTEM_ERROR;
        }
        Optional<Job> outputFiler = task.getOutputFiler();
        if (outputFiler.isPresent() && hasFailed(outputFiler.get().getJob(), taskmasterEnded)) {
            return TesState.SYSTEM_ERROR;
        }
        if (taskmasterCompleted) {
            Integer expectedCount = expectedExecutors.get();
            int expected = expectedCount != null ? expectedCount : executors.size();
            boolean allCompleted =
                    expected > 0
                            && executors.size() == expected
                            && executors.stream()
                                    .allMatch(executor -> hasSucceeded(executor.getJob()));
            return allCompleted ? TesState.COMPLETE : TesState.SYSTEM_ERROR;
        }
        if (hasPendingPod(taskmaster)) {
            return TesState.QUEUED;
        }
        if (isActive(taskmasterJob)) {
            if (executors.isEmpty()) {
                return TesState.INITIALIZING;
            }
            // Each executor's pod can wait as pending, so the task can go
            // from RUNNING back to QUEUED
            if (task.getLastExecutor().map(TaskStateResolver::hasPendingPod).orElse(false)) {
                return TesState.QUEUED;
            }
            return TesState.RUNNING;
        }
        // Just created: until the job controller has synced the job, it has
        // no counts and no pods, and the task has no other jobs (issue #126)
        if (!hasFailedPods(taskmasterJob)
                && !taskmaster.hasPods()
                && executors.isEmpty()
                && !outputFiler.isPresent()) {
            return TesState.QUEUED;
        }
        return TesState.SYSTEM_ERROR;
    }

    private static boolean isCancelled(V1Job job) {
        return Optional.ofNullable(job.getMetadata().getLabels())
                .map(labels -> LABEL_TASKSTATE_VALUE_CANC.equals(labels.get(LABEL_TASKSTATE_KEY)))
                .orElse(false);
    }

    /**
     * Whether a job failed: by its conditions, or, once the taskmaster's job has ended, by its
     * failed pods.
     */
    private static boolean hasFailed(V1Job job, boolean taskmasterEnded) {
        if (hasSucceeded(job)) {
            return false;
        }
        return hasFailedCondition(job) || (taskmasterEnded && hasFailedPods(job));
    }

    private static boolean hasFailedCondition(V1Job job) {
        List<V1JobCondition> conditions =
                Optional.ofNullable(job.getStatus())
                        .map(V1JobStatus::getConditions)
                        .orElse(Collections.emptyList());
        return conditions.stream()
                .anyMatch(
                        condition ->
                                FAILED_CONDITIONS.contains(condition.getType())
                                        && "True".equals(condition.getStatus()));
    }

    private static boolean hasSucceeded(V1Job job) {
        return count(job, V1JobStatus::getSucceeded) > 0;
    }

    private static boolean hasFailedPods(V1Job job) {
        return count(job, V1JobStatus::getFailed) > 0;
    }

    private static boolean isActive(V1Job job) {
        return count(job, V1JobStatus::getActive) > 0;
    }

    private static int count(V1Job job, Function<V1JobStatus, Integer> counter) {
        return Optional.ofNullable(job.getStatus()).map(counter).orElse(0);
    }

    private static boolean hasPendingPod(Job job) {
        String pending = K8sConstants.PodPhase.PENDING.getCode();
        for (V1Pod pod : job.getPods()) {
            if (pod.getStatus() != null && pending.equals(pod.getStatus().getPhase())) {
                return true;
            }
        }
        return false;
    }
}
