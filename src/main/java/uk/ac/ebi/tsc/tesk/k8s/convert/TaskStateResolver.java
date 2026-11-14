package uk.ac.ebi.tsc.tesk.k8s.convert;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_IGNORE_ERROR_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_IGNORE_ERROR_VALUE;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_VALUE_CANC;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodStatus;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.joda.time.DateTime;
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

    /**
     * Reasons of the pod condition DisruptionTarget (Kubernetes 1.26+) that make an executor's
     * failure a preemption: the system stopped the pod, not the executor's command (issue #144).
     */
    static final Set<String> DISRUPTION_REASONS =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    "PreemptionByScheduler",
                                    "TerminationByKubelet",
                                    "DeletionByTaintManager",
                                    "EvictionByEvictionAPI")));

    /** Seconds after a pod's deletion time from which it counts as ended. */
    static final int DELETION_SLACK_SECONDS = 60;

    /** Pod phases of pods that have ended. */
    private static final Set<String> ENDED_POD_PHASES =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    K8sConstants.PodPhase.SUCCEEDED.getCode(),
                                    K8sConstants.PodPhase.FAILED.getCode())));

    private TaskStateResolver() {}

    /**
     * Whether any job of the task (taskmaster, executors, outputs filer) still has a pod that
     * hasn't ended (see {@link #hasEnded}), or one that Kubernetes counts as active. The inputs
     * filer's job isn't read, so it doesn't count.
     */
    private static boolean hasActiveJob(Task task) {
        Stream<Job> jobs =
                Stream.concat(
                        Stream.concat(
                                Stream.of(task.getTaskmaster()), task.getExecutors().stream()),
                        task.getOutputFiler().map(Stream::of).orElseGet(Stream::empty));
        return jobs.anyMatch(
                job ->
                        isActive(job.getJob())
                                || job.getPods().stream().anyMatch(pod -> !hasEnded(pod)));
    }

    /**
     * Whether a pod has ended: it is in phase {@code Succeeded} or {@code Failed}, or its deletion
     * time (the end of its grace period) was more than {@link #DELETION_SLACK_SECONDS} ago. Any
     * other pod (pending, running, terminating, in phase {@code Unknown}, or without a status yet)
     * hasn't. A pod still listed after its deletion time most likely ran on a node that is gone,
     * and Kubernetes removes it only once that node is removed; it doesn't keep a cancelled task
     * canceling. The slack allows for clocks of TESK API and the API server that differ.
     */
    private static boolean hasEnded(V1Pod pod) {
        if (pod.getStatus() != null && ENDED_POD_PHASES.contains(pod.getStatus().getPhase())) {
            return true;
        }
        DateTime deletion =
                Optional.ofNullable(pod.getMetadata())
                        .map(V1ObjectMeta::getDeletionTimestamp)
                        .orElse(null);
        return deletion != null && deletion.plusSeconds(DELETION_SLACK_SECONDS).isBeforeNow();
    }

    /**
     * Whether a failed executor's job failed because Kubernetes disrupted its pods: at least one of
     * its pods (in any phase, e.g. one still terminating) has the condition DisruptionTarget with
     * one of {@link #DISRUPTION_REASONS}, and every pod in phase {@code Failed} has it. Pods that
     * Kubernetes deleted can't be told apart: without pods, the job counts as not disrupted.
     */
    private static boolean wasDisrupted(Job executor) {
        List<V1Pod> pods = executor.getPods();
        boolean anyDisrupted = pods.stream().anyMatch(TaskStateResolver::isDisrupted);
        String failed = K8sConstants.PodPhase.FAILED.getCode();
        boolean failedPodsDisrupted =
                pods.stream()
                        .filter(pod -> pod.getStatus() != null)
                        .filter(pod -> failed.equals(pod.getStatus().getPhase()))
                        .allMatch(TaskStateResolver::isDisrupted);
        return anyDisrupted && failedPodsDisrupted;
    }

    private static boolean isDisrupted(V1Pod pod) {
        List<V1PodCondition> conditions =
                Optional.ofNullable(pod.getStatus())
                        .map(V1PodStatus::getConditions)
                        .orElse(Collections.emptyList());
        return conditions.stream()
                .anyMatch(
                        condition ->
                                "DisruptionTarget".equals(condition.getType())
                                        && "True".equals(condition.getStatus())
                                        && DISRUPTION_REASONS.contains(condition.getReason()));
    }

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
        return resolve(task, expectedExecutors, () -> null);
    }

    /**
     * Derives the state of a task.
     *
     * @param task the task's jobs and their pods
     * @param expectedExecutors supplies the number of executors of the task, or {@code null} if
     *     unknown (then the executor jobs that exist count as all executors); only called once the
     *     taskmaster's job completed
     * @param hasOutputs supplies whether the task has outputs, or {@code null} if unknown; only
     *     called once the taskmaster's job completed after an executor whose failure is ignored
     *     (then, with outputs or if unknown, the task completed only if the outputs filer did, as a
     *     taskmaster can stop at such a failure without uploading the outputs)
     * @return the TES state of the task
     */
    public static TesState resolve(
            Task task, Supplier<Integer> expectedExecutors, Supplier<Boolean> hasOutputs) {
        Job taskmaster = task.getTaskmaster();
        V1Job taskmasterJob = taskmaster.getJob();
        if (isCancelled(taskmasterJob)) {
            return hasActiveJob(task) ? TesState.CANCELING : TesState.CANCELED;
        }

        boolean taskmasterCompleted = hasSucceeded(taskmasterJob);
        // Only by its conditions: between retries, the taskmaster's job has
        // failed pods and none active (such a job gives SYSTEM_ERROR below,
        // as before, but doesn't end the task's other jobs)
        boolean taskmasterFailed = !taskmasterCompleted && hasFailedCondition(taskmasterJob);
        boolean taskmasterEnded = taskmasterCompleted || taskmasterFailed;

        List<Job> executors = task.getExecutors();
        List<Job> failedExecutors =
                executors.stream()
                        .filter(executor -> hasFailed(executor.getJob(), taskmasterEnded))
                        .filter(executor -> !ignoresError(executor.getJob()))
                        .collect(Collectors.toList());
        if (!failedExecutors.isEmpty()) {
            return failedExecutors.stream().allMatch(TaskStateResolver::wasDisrupted)
                    ? TesState.PREEMPTED
                    : TesState.EXECUTOR_ERROR;
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
                            && executors.stream().allMatch(executor -> isDone(executor.getJob()));
            // After an ignored failure, a taskmaster can stop without uploading the outputs
            // (one without support for ignore_error, or one whose failed executor's pods didn't
            // end in time): the outputs filer must have completed, if there are outputs
            boolean ignoredFailure =
                    executors.stream().anyMatch(executor -> !hasSucceeded(executor.getJob()));
            if (allCompleted
                    && ignoredFailure
                    && !Boolean.FALSE.equals(hasOutputs.get())
                    && !outputFiler.map(filer -> hasSucceeded(filer.getJob())).orElse(false)) {
                return TesState.SYSTEM_ERROR;
            }
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

    /**
     * Whether a failure of an executor's job is ignored (TES {@code ignore_error}, issue #159): the
     * job has the annotation {@code ignore-error} with the value {@code "true"}, and the taskmaster
     * didn't end it itself. The taskmaster ends a job whose pod can't start or be scheduled by
     * setting its {@code activeDeadlineSeconds}, so that Kubernetes fails it with the reason {@code
     * DeadlineExceeded}; the executor may then never have run, and the taskmaster stops the task.
     */
    private static boolean ignoresError(V1Job job) {
        boolean annotated =
                Optional.ofNullable(job.getMetadata())
                        .map(V1ObjectMeta::getAnnotations)
                        .map(
                                annotations ->
                                        ANN_IGNORE_ERROR_VALUE.equals(
                                                annotations.get(ANN_IGNORE_ERROR_KEY)))
                        .orElse(false);
        return annotated && !endedByDeadline(job);
    }

    /**
     * Whether an executor's job is done, once the taskmaster's job has completed: it succeeded, or
     * it failed and its failure is ignored ({@link #ignoresError}).
     */
    private static boolean isDone(V1Job job) {
        // The taskmaster's job has ended, so failed pods alone count as a failure
        boolean taskmasterEnded = true;
        return hasSucceeded(job) || (ignoresError(job) && hasFailed(job, taskmasterEnded));
    }

    /**
     * Whether the taskmaster ended the job itself: it fails a job whose pod can't start or be
     * scheduled by setting {@code activeDeadlineSeconds} (to 1, {@code FAIL_DEADLINE} in
     * tesk-core's {@code job.py}), so that Kubernetes fails it with the reason {@code
     * DeadlineExceeded}. TESK API sets no deadline on executor jobs.
     */
    private static boolean endedByDeadline(V1Job job) {
        boolean hasDeadline =
                Optional.ofNullable(job.getSpec())
                        .map(V1JobSpec::getActiveDeadlineSeconds)
                        .isPresent();
        List<V1JobCondition> conditions =
                Optional.ofNullable(job.getStatus())
                        .map(V1JobStatus::getConditions)
                        .orElse(Collections.emptyList());
        return hasDeadline
                || conditions.stream()
                        .anyMatch(condition -> "DeadlineExceeded".equals(condition.getReason()));
    }

    private static boolean isCancelled(V1Job job) {
        return isLabelledCancelled(job.getMetadata());
    }

    /** Whether a taskmaster's job or pod is labelled as cancelled. */
    public static boolean isLabelledCancelled(V1ObjectMeta metadata) {
        return Optional.ofNullable(metadata.getLabels())
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
