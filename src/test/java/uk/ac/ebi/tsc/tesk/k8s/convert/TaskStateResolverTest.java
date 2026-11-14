package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.JOB_NAME_FILER_SUF;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_VALUE_CANC;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Job;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Task;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;

/**
 * Table-driven tests of {@link TaskStateResolver}: every combination of the states of a task's jobs
 * (taskmaster, two executors, outputs filer, and the cancellation label) against a decision table,
 * first matching row wins.
 */
class TaskStateResolverTest {

    private static final String TASK = "task-1234";

    /** States of a job (and its pod) as the TESK API reads them. */
    enum JobState {
        /** The job doesn't exist. */
        ABSENT,
        /**
         * Just created: no counts, no conditions and no pod yet, until the job controller has
         * synced it (issue #126).
         */
        NEW,
        /** Active, with a pending pod. */
        PENDING,
        /** Active, with a running pod. */
        RUNNING,
        /** A pod succeeded; condition Complete. */
        SUCCEEDED,
        /** A pod failed; condition Failed. */
        FAILED,
        /** Condition FailureTarget (Kubernetes 1.31+), before Failed; pod still terminating. */
        FAILURE_TARGET,
        /** A pod failed, no condition yet (older clusters) and no pod active. */
        FAILED_PODS,
        /** A pod failed and another is active: the job retries. */
        RETRYING,
        /**
         * A pod failed with the condition DisruptionTarget (e.g. preempted or evicted); condition
         * Failed (issue #144).
         */
        PREEMPTED
    }

    /** Wildcard of the decision table. */
    private static final Set<JobState> ANY = EnumSet.allOf(JobState.class);

    private static final Set<JobState> FAILING =
            EnumSet.of(JobState.FAILED, JobState.FAILURE_TARGET);
    /** Job states with a pod that is pending or running (also a terminating one). */
    private static final Set<JobState> ACTIVE_POD =
            EnumSet.of(
                    JobState.PENDING, JobState.RUNNING, JobState.FAILURE_TARGET, JobState.RETRYING);
    /** Taskmaster states that end the task's other jobs; failed pods alone may still be retried. */
    private static final Set<JobState> ENDED_TM =
            EnumSet.of(
                    JobState.SUCCEEDED,
                    JobState.FAILED,
                    JobState.FAILURE_TARGET,
                    JobState.PREEMPTED);

    private static final Set<JobState> TASKMASTER_STATES =
            EnumSet.complementOf(EnumSet.of(JobState.ABSENT));

    /** A row of the decision table: the states each job may be in, and the expected task state. */
    private static final class Row {
        /** Whether the task is labelled as cancelled; {@code null} for either. */
        final Boolean cancelled;

        final Set<JobState> taskmaster;
        final Set<JobState> executor0;
        final Set<JobState> executor1;
        final Set<JobState> filer;
        final TesState expected;

        Row(
                Boolean cancelled,
                Set<JobState> taskmaster,
                Set<JobState> executor0,
                Set<JobState> executor1,
                Set<JobState> filer,
                TesState expected) {
            this.cancelled = cancelled;
            this.taskmaster = taskmaster;
            this.executor0 = executor0;
            this.executor1 = executor1;
            this.filer = filer;
            this.expected = expected;
        }

        boolean matches(boolean c, JobState tm, JobState e0, JobState e1, JobState f) {
            return (cancelled == null || cancelled == c)
                    && taskmaster.contains(tm)
                    && executor0.contains(e0)
                    && executor1.contains(e1)
                    && filer.contains(f);
        }
    }

    private static final Boolean NOT_CANCELLED = false;

    private static Set<JobState> of(JobState first, JobState... rest) {
        return EnumSet.of(first, rest);
    }

    /** The decision table; the first row that matches gives the state. */
    private static final List<Row> TABLE =
            Arrays.asList(
                    // Cancelled tasks are canceling while any of their jobs has a pending or
                    // running pod, then cancelled (issue #144)
                    new Row(true, ACTIVE_POD, ANY, ANY, ANY, TesState.CANCELING),
                    new Row(true, ANY, ACTIVE_POD, ANY, ANY, TesState.CANCELING),
                    new Row(true, ANY, ANY, ACTIVE_POD, ANY, TesState.CANCELING),
                    new Row(true, ANY, ANY, ANY, ACTIVE_POD, TesState.CANCELING),
                    new Row(true, ANY, ANY, ANY, ANY, TesState.CANCELED),
                    // A failed executor, whichever, is an executor error
                    new Row(NOT_CANCELLED, ANY, FAILING, ANY, ANY, TesState.EXECUTOR_ERROR),
                    new Row(NOT_CANCELLED, ANY, ANY, FAILING, ANY, TesState.EXECUTOR_ERROR),
                    // Failed pods without a condition count once the taskmaster has ended
                    new Row(
                            NOT_CANCELLED,
                            ENDED_TM,
                            of(JobState.FAILED_PODS, JobState.RETRYING),
                            ANY,
                            ANY,
                            TesState.EXECUTOR_ERROR),
                    new Row(
                            NOT_CANCELLED,
                            ENDED_TM,
                            ANY,
                            of(JobState.FAILED_PODS, JobState.RETRYING),
                            ANY,
                            TesState.EXECUTOR_ERROR),
                    // An executor whose pods were disrupted is preempted, if no executor
                    // failed otherwise (issue #144)
                    new Row(
                            NOT_CANCELLED,
                            ANY,
                            of(JobState.PREEMPTED),
                            ANY,
                            ANY,
                            TesState.PREEMPTED),
                    new Row(
                            NOT_CANCELLED,
                            ANY,
                            ANY,
                            of(JobState.PREEMPTED),
                            ANY,
                            TesState.PREEMPTED),
                    // A failed taskmaster or filer is a system error (a taskmaster with
                    // failed pods and none active, maybe between retries, too, as before)
                    new Row(
                            NOT_CANCELLED,
                            of(
                                    JobState.FAILED,
                                    JobState.FAILURE_TARGET,
                                    JobState.FAILED_PODS,
                                    JobState.PREEMPTED),
                            ANY,
                            ANY,
                            ANY,
                            TesState.SYSTEM_ERROR),
                    // A disrupted filer is a system error: only executors are preempted
                    new Row(
                            NOT_CANCELLED,
                            ANY,
                            ANY,
                            ANY,
                            of(JobState.FAILED, JobState.FAILURE_TARGET, JobState.PREEMPTED),
                            TesState.SYSTEM_ERROR),
                    new Row(
                            NOT_CANCELLED,
                            ENDED_TM,
                            ANY,
                            ANY,
                            of(JobState.FAILED_PODS, JobState.RETRYING),
                            TesState.SYSTEM_ERROR),
                    // Complete only if every executor completed; never from an earlier executor
                    new Row(
                            NOT_CANCELLED,
                            of(JobState.SUCCEEDED),
                            of(JobState.SUCCEEDED),
                            of(JobState.SUCCEEDED),
                            ANY,
                            TesState.COMPLETE),
                    new Row(
                            NOT_CANCELLED,
                            of(JobState.SUCCEEDED),
                            ANY,
                            ANY,
                            ANY,
                            TesState.SYSTEM_ERROR),
                    // Taskmaster still pending, new (no other jobs yet) or running
                    new Row(NOT_CANCELLED, of(JobState.PENDING), ANY, ANY, ANY, TesState.QUEUED),
                    new Row(
                            NOT_CANCELLED,
                            of(JobState.NEW),
                            of(JobState.ABSENT),
                            of(JobState.ABSENT),
                            of(JobState.ABSENT),
                            TesState.QUEUED),
                    new Row(
                            NOT_CANCELLED,
                            of(JobState.RUNNING, JobState.RETRYING),
                            of(JobState.ABSENT),
                            of(JobState.ABSENT),
                            ANY,
                            TesState.INITIALIZING),
                    new Row(
                            NOT_CANCELLED,
                            of(JobState.RUNNING, JobState.RETRYING),
                            ANY,
                            of(JobState.PENDING),
                            ANY,
                            TesState.QUEUED),
                    new Row(
                            NOT_CANCELLED,
                            of(JobState.RUNNING, JobState.RETRYING),
                            of(JobState.PENDING),
                            of(JobState.ABSENT),
                            ANY,
                            TesState.QUEUED),
                    new Row(
                            NOT_CANCELLED,
                            of(JobState.RUNNING, JobState.RETRYING),
                            ANY,
                            ANY,
                            ANY,
                            TesState.RUNNING),
                    // A taskmaster's job without pods but with other jobs of the task isn't new
                    new Row(NOT_CANCELLED, of(JobState.NEW), ANY, ANY, ANY, TesState.SYSTEM_ERROR));

    static Stream<Arguments> combinations() {
        List<Arguments> combinations = new ArrayList<>();
        for (boolean cancelled : new boolean[] {false, true}) {
            for (JobState taskmaster : TASKMASTER_STATES) {
                for (JobState executor0 : JobState.values()) {
                    for (JobState executor1 : JobState.values()) {
                        for (JobState filer : JobState.values()) {
                            combinations.add(
                                    Arguments.of(
                                            cancelled, taskmaster, executor0, executor1, filer));
                        }
                    }
                }
            }
        }
        return combinations.stream();
    }

    /** The index of the first row of the table that matches. */
    private static int matchingRow(
            boolean cancelled,
            JobState taskmaster,
            JobState executor0,
            JobState executor1,
            JobState filer) {
        for (int i = 0; i < TABLE.size(); i++) {
            if (TABLE.get(i).matches(cancelled, taskmaster, executor0, executor1, filer)) {
                return i;
            }
        }
        throw new AssertionError("No row of the table matches");
    }

    @ParameterizedTest(name = "cancelled={0}, taskmaster={1}, executors={2},{3}, filer={4}")
    @MethodSource("combinations")
    void resolvesEveryCombination(
            boolean cancelled,
            JobState taskmaster,
            JobState executor0,
            JobState executor1,
            JobState filer) {
        Task task = task(cancelled, taskmaster, executor0, executor1, filer);
        assertThat(TaskStateResolver.resolve(task, () -> 2))
                .isEqualTo(
                        TABLE.get(matchingRow(cancelled, taskmaster, executor0, executor1, filer))
                                .expected);
    }

    @Test
    void everyRowOfTheTableIsReached() {
        Set<Integer> reached = new HashSet<>();
        combinations()
                .forEach(
                        arguments -> {
                            Object[] a = arguments.get();
                            reached.add(
                                    matchingRow(
                                            (Boolean) a[0],
                                            (JobState) a[1],
                                            (JobState) a[2],
                                            (JobState) a[3],
                                            (JobState) a[4]));
                        });
        assertThat(reached).hasSize(TABLE.size());
    }

    /**
     * The last executor's job is missing (e.g., deleted): the task isn't complete, whatever the
     * state of the executors before it.
     */
    @Test
    void missingLastExecutorIsNotComplete() {
        Task task =
                task(
                        false,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED,
                        JobState.ABSENT,
                        JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.SYSTEM_ERROR);
    }

    /** The first executor failed and the second one never ran: an executor error. */
    @Test
    void failedFirstExecutorIsExecutorError() {
        Task task =
                task(false, JobState.SUCCEEDED, JobState.FAILED, JobState.ABSENT, JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.EXECUTOR_ERROR);
    }

    /** All executors and the outputs filer completed: complete. */
    @Test
    void allJobsCompletedIsComplete() {
        Task task =
                task(
                        false,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.COMPLETE);
    }

    /** The number of executors is read only once the taskmaster's job completed. */
    @Test
    void executorCountIsReadOnlyWhenNeeded() {
        Task task =
                task(false, JobState.RUNNING, JobState.RUNNING, JobState.ABSENT, JobState.ABSENT);
        assertThat(
                        TaskStateResolver.resolve(
                                task,
                                () -> {
                                    throw new AssertionError("read the number of executors");
                                }))
                .isEqualTo(TesState.RUNNING);
    }

    /**
     * Without the number of executors (no task stored with the taskmaster), the executor jobs that
     * exist count as all executors.
     */
    @Test
    void unknownExecutorCountUsesExistingJobs() {
        Task task =
                task(
                        false,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED,
                        JobState.ABSENT,
                        JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> null)).isEqualTo(TesState.COMPLETE);
        Task none =
                task(false, JobState.SUCCEEDED, JobState.ABSENT, JobState.ABSENT, JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(none, () -> null)).isEqualTo(TesState.SYSTEM_ERROR);
    }

    /** Conditions whose status isn't "True" don't count. */
    @Test
    void conditionsThatAreNotTrueDontCount() {
        Task task =
                task(false, JobState.RUNNING, JobState.RUNNING, JobState.ABSENT, JobState.ABSENT);
        task.getExecutors()
                .get(0)
                .getJob()
                .getStatus()
                .addConditionsItem(new V1JobCondition().type("FailureTarget").status("False"));
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.RUNNING);
    }

    /** Conditions other than failure ones don't make a job failed. */
    @Test
    void otherConditionsDontCount() {
        Task task =
                task(false, JobState.RUNNING, JobState.RUNNING, JobState.ABSENT, JobState.ABSENT);
        task.getExecutors()
                .get(0)
                .getJob()
                .getStatus()
                .addConditionsItem(new V1JobCondition().type("Suspended").status("True"));
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.RUNNING);
    }

    /** A pod without a status (just created) isn't pending. */
    @Test
    void podWithoutStatusIsNotPending() {
        Task task =
                task(false, JobState.RUNNING, JobState.RUNNING, JobState.ABSENT, JobState.ABSENT);
        task.getTaskmaster().getPods().get(0).setStatus(null);
        task.getExecutors().get(0).getPods().get(0).setStatus(null);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.RUNNING);
    }

    /**
     * A taskmaster's job without a status object at all (the state NEW of the table has an empty
     * one), just created, is queued (issue #126).
     */
    @Test
    void newTaskmasterWithoutStatusIsQueued() {
        Task task = new Task(new Job(new V1Job().metadata(new V1ObjectMeta().name(TASK))));
        assertThat(TaskStateResolver.resolve(task, () -> 1)).isEqualTo(TesState.QUEUED);
    }

    /**
     * A taskmaster's job with a pod that isn't pending, but not yet counted as active, isn't
     * queued: only a job without pods is new.
     */
    @Test
    void uncountedPodIsNotNew() {
        Task task = task(false, JobState.NEW, JobState.ABSENT, JobState.ABSENT, JobState.ABSENT);
        task.getTaskmaster()
                .addPod(
                        new V1Pod()
                                .metadata(new V1ObjectMeta().name(TASK + "-pod"))
                                .status(new V1PodStatus().phase("Running")));
        assertThat(TaskStateResolver.resolve(task, () -> 1)).isEqualTo(TesState.SYSTEM_ERROR);
    }

    /**
     * A taskmaster's job without pods but with failed ones counted (e.g. between retries, once the
     * failed pods are gone) isn't new: a system error, as before.
     */
    @Test
    void failedCountWithoutPodsIsNotNew() {
        Task task = task(false, JobState.NEW, JobState.ABSENT, JobState.ABSENT, JobState.ABSENT);
        task.getTaskmaster().getJob().getStatus().failed(1);
        assertThat(TaskStateResolver.resolve(task, () -> 1)).isEqualTo(TesState.SYSTEM_ERROR);
    }

    /**
     * Every reason of DisruptionTarget counts as preempted: the scheduler's preemption, the
     * kubelet's termination (e.g. node shutdown or pressure), the taint manager and the Eviction
     * API (e.g. a drain); a pod that is still terminating counts, too (issue #144).
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "PreemptionByScheduler",
                "TerminationByKubelet",
                "DeletionByTaintManager",
                "EvictionByEvictionAPI"
            })
    void everyDisruptionReasonIsPreempted(String reason) {
        assertThat(TaskStateResolver.DISRUPTION_REASONS).contains(reason);
        Task failed = failedExecutor(failedPod(disruption(reason)));
        assertThat(TaskStateResolver.resolve(failed, () -> 1)).isEqualTo(TesState.PREEMPTED);
        Task terminating =
                failedExecutor(
                        new V1PodStatus().phase("Running").addConditionsItem(disruption(reason)));
        assertThat(TaskStateResolver.resolve(terminating, () -> 1)).isEqualTo(TesState.PREEMPTED);
    }

    /** The test above covers every reason that counts as a disruption. */
    @Test
    void disruptionReasonsAreTested() {
        assertThat(TaskStateResolver.DISRUPTION_REASONS).hasSize(4);
    }

    /**
     * Only pods in phase Failed must have been disrupted: a failed pod without the condition next
     * to a running one with it is an executor error (issue #144).
     */
    @Test
    void failedPodWithoutDisruptionIsExecutorError() {
        V1PodStatus terminating =
                new V1PodStatus()
                        .phase("Running")
                        .addConditionsItem(disruption("PreemptionByScheduler"));
        assertThat(TaskStateResolver.resolve(failedExecutor(failedPod(), terminating), () -> 1))
                .isEqualTo(TesState.EXECUTOR_ERROR);
    }

    /**
     * Without a disruption that TESK knows, with one whose status isn't "True", or without pods
     * (e.g. deleted), a failed executor is an executor error (issue #144).
     */
    @Test
    void otherFailuresAreExecutorErrors() {
        assertThat(TaskStateResolver.resolve(failedExecutor(failedPod()), () -> 1))
                .isEqualTo(TesState.EXECUTOR_ERROR);
        assertThat(
                        TaskStateResolver.resolve(
                                failedExecutor(failedPod(disruption("SomethingElse"))), () -> 1))
                .isEqualTo(TesState.EXECUTOR_ERROR);
        assertThat(
                        TaskStateResolver.resolve(
                                failedExecutor(
                                        failedPod(
                                                disruption("PreemptionByScheduler").type("Ready"))),
                                () -> 1))
                .isEqualTo(TesState.EXECUTOR_ERROR);
        assertThat(
                        TaskStateResolver.resolve(
                                failedExecutor(
                                        failedPod(
                                                disruption("PreemptionByScheduler")
                                                        .status("False"))),
                                () -> 1))
                .isEqualTo(TesState.EXECUTOR_ERROR);
        assertThat(TaskStateResolver.resolve(failedExecutor(), () -> 1))
                .isEqualTo(TesState.EXECUTOR_ERROR);
        assertThat(TaskStateResolver.resolve(failedExecutor((V1PodStatus) null), () -> 1))
                .isEqualTo(TesState.EXECUTOR_ERROR);
    }

    /**
     * An executor whose job retried is preempted only if every failed pod was disrupted: one that
     * failed on its own makes it an executor error (issue #144).
     */
    @Test
    void retriedExecutorIsPreemptedOnlyIfEveryFailedPodWasDisrupted() {
        V1PodStatus preempted = failedPod(disruption("PreemptionByScheduler"));
        V1PodStatus evicted = failedPod(disruption("EvictionByEvictionAPI"));
        assertThat(TaskStateResolver.resolve(failedExecutor(preempted, evicted), () -> 1))
                .isEqualTo(TesState.PREEMPTED);
        assertThat(TaskStateResolver.resolve(failedExecutor(preempted, failedPod()), () -> 1))
                .isEqualTo(TesState.EXECUTOR_ERROR);
    }

    /** One executor failed on its own, another was preempted: an executor error (issue #144). */
    @Test
    void executorErrorWinsOverPreempted() {
        Task task =
                task(
                        false,
                        JobState.SUCCEEDED,
                        JobState.PREEMPTED,
                        JobState.FAILED,
                        JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.EXECUTOR_ERROR);
    }

    /**
     * A cancelled task is canceling while a pod hasn't ended (also one without a status yet, or in
     * phase Unknown), and cancelled once all pods ended (issue #144).
     */
    @Test
    void cancelledTaskIsCancelingUntilPodsEnded() {
        Task task = task(true, JobState.FAILED, JobState.FAILED, JobState.ABSENT, JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> 1)).isEqualTo(TesState.CANCELED);
        task.getExecutors().get(0).addPod(pod(TASK + "-ex-00-pod-2", null));
        assertThat(TaskStateResolver.resolve(task, () -> 1)).isEqualTo(TesState.CANCELING);
        Task unknown =
                task(true, JobState.FAILED, JobState.FAILED, JobState.ABSENT, JobState.ABSENT);
        unknown.getExecutors()
                .get(0)
                .addPod(pod(TASK + "-ex-00-pod-2", new V1PodStatus().phase("Unknown")));
        assertThat(TaskStateResolver.resolve(unknown, () -> 1)).isEqualTo(TesState.CANCELING);
    }

    /**
     * A pod that is still listed a minute after its deletion time (e.g. on a node that is gone) has
     * ended; one within its grace period, or just after it, hasn't (issue #144).
     */
    @Test
    void podPastItsDeletionTimeHasEnded() {
        Task stuck = task(true, JobState.FAILED, JobState.FAILED, JobState.ABSENT, JobState.ABSENT);
        V1Pod pod = pod(TASK + "-ex-00-pod-2", new V1PodStatus().phase("Running"));
        int slack = TaskStateResolver.DELETION_SLACK_SECONDS;
        pod.getMetadata().deletionTimestamp(DateTime.now().minusSeconds(slack + 10));
        stuck.getExecutors().get(0).addPod(pod);
        assertThat(TaskStateResolver.resolve(stuck, () -> 1)).isEqualTo(TesState.CANCELED);
        pod.getMetadata().deletionTimestamp(DateTime.now().minusSeconds(slack - 10));
        assertThat(TaskStateResolver.resolve(stuck, () -> 1)).isEqualTo(TesState.CANCELING);
        pod.getMetadata().deletionTimestamp(DateTime.now().plusMinutes(1));
        assertThat(TaskStateResolver.resolve(stuck, () -> 1)).isEqualTo(TesState.CANCELING);
    }

    /**
     * A job that Kubernetes still counts as active is canceling, also if its pods weren't read
     * (issue #144).
     */
    @Test
    void activeCountWithoutPodsIsCanceling() {
        Task task = task(true, JobState.FAILED, JobState.FAILED, JobState.ABSENT, JobState.ABSENT);
        task.setOutputFiler(
                new Job(
                        new V1Job()
                                .metadata(new V1ObjectMeta().name(TASK + JOB_NAME_FILER_SUF))
                                .status(new V1JobStatus().active(1))));
        assertThat(TaskStateResolver.resolve(task, () -> 1)).isEqualTo(TesState.CANCELING);
    }

    /**
     * An executor that ignores errors (TES ignore_error, issue #159) and failed doesn't make the
     * task fail: the task's state comes from the other jobs, whichever executor it is, also a
     * preempted one.
     */
    @ParameterizedTest(name = "executor {0} {1}, taskmaster {2}: {3}")
    @CsvSource({
        "0, FAILED, SUCCEEDED, COMPLETE",
        "1, FAILED, SUCCEEDED, COMPLETE",
        "0, PREEMPTED, SUCCEEDED, COMPLETE",
        "1, PREEMPTED, SUCCEEDED, COMPLETE",
        "0, FAILED, RUNNING, RUNNING",
        "1, FAILED, RUNNING, RUNNING",
        "0, FAILED_PODS, SUCCEEDED, COMPLETE",
    })
    void ignoredExecutorFailureCountsAsDone(
            int ignored, JobState failure, JobState taskmaster, TesState expected) {
        JobState ex0 = ignored == 0 ? failure : JobState.SUCCEEDED;
        JobState ex1 = ignored == 1 ? failure : JobState.SUCCEEDED;
        JobState filer = taskmaster == JobState.RUNNING ? JobState.RUNNING : JobState.SUCCEEDED;
        Task task = task(false, taskmaster, ex0, ex1, filer);
        ignoreError(task, ignored, "true");
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(expected);
    }

    /**
     * An ignored failure doesn't make up for a missing executor: if a later executor's job is
     * missing (e.g., a taskmaster without support for ignore_error stopped at the failure), the
     * task has a system error (issue #159).
     */
    @Test
    void ignoredFailureWithMissingLaterExecutor() {
        Task missing =
                task(false, JobState.SUCCEEDED, JobState.FAILED, JobState.ABSENT, JobState.ABSENT);
        ignoreError(missing, 0, "true");
        assertThat(TaskStateResolver.resolve(missing, () -> 2, () -> false))
                .isEqualTo(TesState.SYSTEM_ERROR);
    }

    /**
     * An executor that ignores errors but whose job neither succeeded nor failed isn't done: once
     * the taskmaster's job completed, the task has a system error (issue #159).
     */
    @Test
    void ignoringExecutorNotEndedIsNotDone() {
        Task task =
                task(
                        false,
                        JobState.SUCCEEDED,
                        JobState.RUNNING,
                        JobState.SUCCEEDED,
                        JobState.ABSENT);
        ignoreError(task, 0, "true");
        assertThat(TaskStateResolver.resolve(task, () -> 2, () -> false))
                .isEqualTo(TesState.SYSTEM_ERROR);
    }

    /**
     * After an ignored failure of the last executor, a task with outputs (or unknown ones)
     * completed only if its outputs filer did, as the taskmaster may have stopped at the failure; a
     * task without outputs needs none (issue #159).
     */
    @ParameterizedTest(name = "outputs {0}, filer {1}: {2}")
    @CsvSource({
        "true, ABSENT, SYSTEM_ERROR",
        ", ABSENT, SYSTEM_ERROR",
        "false, ABSENT, COMPLETE",
        "true, SUCCEEDED, COMPLETE",
        ", SUCCEEDED, COMPLETE",
        "true, NEW, SYSTEM_ERROR",
    })
    void ignoredFailureOfLastExecutorNeedsOutputsFiler(
            Boolean hasOutputs, JobState filer, TesState expected) {
        Task task = task(false, JobState.SUCCEEDED, JobState.SUCCEEDED, JobState.FAILED, filer);
        ignoreError(task, 1, "true");
        assertThat(TaskStateResolver.resolve(task, () -> 2, () -> hasOutputs)).isEqualTo(expected);
    }

    /** Without ignored failures, the outputs filer's job isn't needed for COMPLETE, as before. */
    @Test
    void completeWithoutIgnoredFailureNeedsNoOutputsFiler() {
        Task task =
                task(
                        false,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED,
                        JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> 2, () -> true))
                .isEqualTo(TesState.COMPLETE);
    }

    /** Errors of executors without the annotation value "true" aren't ignored (issue #159). */
    @ParameterizedTest
    @ValueSource(strings = {"false", "True", ""})
    void otherAnnotationValuesDontIgnoreErrors(String value) {
        Task task =
                task(
                        false,
                        JobState.SUCCEEDED,
                        JobState.FAILED,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED);
        ignoreError(task, 0, value);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.EXECUTOR_ERROR);
    }

    /** An executor that fails without ignoring errors still fails the task (issue #159). */
    @Test
    void laterExecutorErrorIsNotIgnored() {
        Task task =
                task(false, JobState.SUCCEEDED, JobState.FAILED, JobState.FAILED, JobState.ABSENT);
        ignoreError(task, 0, "true");
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.EXECUTOR_ERROR);
    }

    /**
     * An executor's job that the taskmaster ended itself (it set activeDeadlineSeconds, e.g. as the
     * pod couldn't start; Kubernetes then fails the job with the reason DeadlineExceeded) isn't
     * ignored: the executor may never have run, and the taskmaster stopped (issue #159).
     */
    @Test
    void executorEndedByTaskmasterIsNotIgnored() {
        Task deadline =
                task(
                        false,
                        JobState.SUCCEEDED,
                        JobState.FAILED,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED);
        ignoreError(deadline, 0, "true");
        deadline.getExecutors().get(0).getJob().spec(new V1JobSpec().activeDeadlineSeconds(1L));
        assertThat(TaskStateResolver.resolve(deadline, () -> 2)).isEqualTo(TesState.EXECUTOR_ERROR);

        Task reason =
                task(
                        false,
                        JobState.SUCCEEDED,
                        JobState.FAILED,
                        JobState.SUCCEEDED,
                        JobState.SUCCEEDED);
        ignoreError(reason, 0, "true");
        reason.getExecutors()
                .get(0)
                .getJob()
                .getStatus()
                .getConditions()
                .get(0)
                .reason("DeadlineExceeded");
        assertThat(TaskStateResolver.resolve(reason, () -> 2)).isEqualTo(TesState.EXECUTOR_ERROR);
    }

    /** Gives an executor's job the annotation with which TESK passes ignore_error. */
    private static void ignoreError(Task task, int executor, String value) {
        task.getExecutors()
                .get(executor)
                .getJob()
                .getMetadata()
                .putAnnotationsItem("ignore-error", value); // the contract with the taskmaster
    }

    private static Task task(
            boolean cancelled,
            JobState taskmaster,
            JobState executor0,
            JobState executor1,
            JobState filer) {
        Job taskmasterJob = job(TASK, taskmaster);
        if (cancelled) {
            taskmasterJob
                    .getJob()
                    .getMetadata()
                    .putLabelsItem(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        }
        Task task = new Task(taskmasterJob);
        if (executor0 != JobState.ABSENT) {
            task.addExecutor(job(TASK + "-ex-00", executor0));
        }
        if (executor1 != JobState.ABSENT) {
            task.addExecutor(job(TASK + "-ex-01", executor1));
        }
        if (filer != JobState.ABSENT) {
            task.setOutputFiler(job(TASK + JOB_NAME_FILER_SUF, filer));
        }
        return task;
    }

    private static Job job(String name, JobState state) {
        V1JobStatus status = new V1JobStatus();
        String podPhase = null;
        switch (state) {
            case NEW:
                return new Job(new V1Job().metadata(new V1ObjectMeta().name(name)).status(status));
            case PENDING:
                status.active(1);
                podPhase = "Pending";
                break;
            case RUNNING:
                status.active(1);
                podPhase = "Running";
                break;
            case SUCCEEDED:
                status.succeeded(1)
                        .addConditionsItem(new V1JobCondition().type("Complete").status("True"));
                podPhase = "Succeeded";
                break;
            case FAILED:
                status.failed(1)
                        .addConditionsItem(new V1JobCondition().type("Failed").status("True"));
                podPhase = "Failed";
                break;
            case FAILURE_TARGET:
                status.addConditionsItem(new V1JobCondition().type("FailureTarget").status("True"));
                podPhase = "Running";
                break;
            case FAILED_PODS:
                status.failed(1);
                podPhase = "Failed";
                break;
            case RETRYING:
                status.failed(1).active(1);
                podPhase = "Running";
                break;
            case PREEMPTED:
                status.failed(1)
                        .addConditionsItem(new V1JobCondition().type("Failed").status("True"));
                return withPod(
                        name,
                        status,
                        new V1PodStatus()
                                .phase("Failed")
                                .addConditionsItem(disruption("PreemptionByScheduler")));
            default:
                throw new IllegalArgumentException(state.name());
        }
        return withPod(name, status, new V1PodStatus().phase(podPhase));
    }

    private static Job withPod(String name, V1JobStatus status, V1PodStatus podStatus) {
        Job job = new Job(new V1Job().metadata(new V1ObjectMeta().name(name)).status(status));
        job.addPod(pod(name + "-pod", podStatus));
        return job;
    }

    private static V1Pod pod(String name, V1PodStatus status) {
        return new V1Pod()
                .metadata(new V1ObjectMeta().name(name))
                .spec(new V1PodSpec())
                .status(status);
    }

    /** The condition that Kubernetes 1.26+ sets on a pod that it disrupts, with this reason. */
    private static V1PodCondition disruption(String reason) {
        return new V1PodCondition().type("DisruptionTarget").status("True").reason(reason);
    }

    /** A task whose only executor failed, with pods of these statuses. */
    private static Task failedExecutor(V1PodStatus... podStatuses) {
        Task task =
                task(false, JobState.RUNNING, JobState.FAILED, JobState.ABSENT, JobState.ABSENT);
        Job executor = task.getExecutors().get(0);
        executor.getPods().clear();
        for (int i = 0; i < podStatuses.length; i++) {
            executor.addPod(pod(TASK + "-ex-00-pod-" + i, podStatuses[i]));
        }
        return task;
    }

    private static V1PodStatus failedPod(V1PodCondition... conditions) {
        V1PodStatus status = new V1PodStatus().phase("Failed");
        for (V1PodCondition condition : conditions) {
            status.addConditionsItem(condition);
        }
        return status;
    }
}
