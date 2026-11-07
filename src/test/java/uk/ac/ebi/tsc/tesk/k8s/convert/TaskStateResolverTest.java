package uk.ac.ebi.tsc.tesk.k8s.convert;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Job;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Task;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.JOB_NAME_FILER_SUF;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_VALUE_CANC;

/**
 * Table-driven tests of {@link TaskStateResolver}: every combination of the
 * states of a task's jobs (taskmaster, two executors, outputs filer, and the
 * cancellation label) against a decision table, first matching row wins.
 */
class TaskStateResolverTest {

    private static final String TASK = "task-1234";

    /**
     * States of a job (and its pod) as the TESK API reads them.
     */
    enum JobState {
        /** The job doesn't exist. */
        ABSENT,
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
        RETRYING
    }

    /** Wildcard of the decision table. */
    private static final Set<JobState> ANY = EnumSet.allOf(JobState.class);
    private static final Set<JobState> FAILING = EnumSet.of(JobState.FAILED, JobState.FAILURE_TARGET);
    /** Taskmaster states that end the task's other jobs; failed pods alone may still be retried. */
    private static final Set<JobState> ENDED_TM = EnumSet.of(JobState.SUCCEEDED, JobState.FAILED,
            JobState.FAILURE_TARGET);
    private static final Set<JobState> TASKMASTER_STATES = EnumSet.complementOf(EnumSet.of(JobState.ABSENT));

    /**
     * A row of the decision table: the states each job may be in, and the
     * expected task state.
     */
    private static final class Row {
        /** Whether the task is labelled as cancelled; {@code null} for either. */
        final Boolean cancelled;
        final Set<JobState> taskmaster;
        final Set<JobState> executor0;
        final Set<JobState> executor1;
        final Set<JobState> filer;
        final TesState expected;

        Row(Boolean cancelled, Set<JobState> taskmaster, Set<JobState> executor0,
            Set<JobState> executor1, Set<JobState> filer, TesState expected) {
            this.cancelled = cancelled;
            this.taskmaster = taskmaster;
            this.executor0 = executor0;
            this.executor1 = executor1;
            this.filer = filer;
            this.expected = expected;
        }

        boolean matches(boolean c, JobState tm, JobState e0, JobState e1, JobState f) {
            return (cancelled == null || cancelled == c) && taskmaster.contains(tm) && executor0.contains(e0)
                    && executor1.contains(e1) && filer.contains(f);
        }
    }

    private static final Boolean NOT_CANCELLED = false;

    private static Set<JobState> of(JobState first, JobState... rest) {
        return EnumSet.of(first, rest);
    }

    /**
     * The decision table; the first row that matches gives the state.
     */
    private static final List<Row> TABLE = Arrays.asList(
            // Cancelled tasks stay cancelled
            new Row(true, ANY, ANY, ANY, ANY, TesState.CANCELED),
            // A failed executor, whichever, is an executor error
            new Row(NOT_CANCELLED, ANY, FAILING, ANY, ANY, TesState.EXECUTOR_ERROR),
            new Row(NOT_CANCELLED, ANY, ANY, FAILING, ANY, TesState.EXECUTOR_ERROR),
            // Failed pods without a condition count once the taskmaster has ended
            new Row(NOT_CANCELLED, ENDED_TM, of(JobState.FAILED_PODS, JobState.RETRYING), ANY, ANY,
                    TesState.EXECUTOR_ERROR),
            new Row(NOT_CANCELLED, ENDED_TM, ANY, of(JobState.FAILED_PODS, JobState.RETRYING), ANY,
                    TesState.EXECUTOR_ERROR),
            // A failed taskmaster or filer is a system error (a taskmaster with
            // failed pods and none active, maybe between retries, too, as before)
            new Row(NOT_CANCELLED, of(JobState.FAILED, JobState.FAILURE_TARGET, JobState.FAILED_PODS), ANY, ANY, ANY,
                    TesState.SYSTEM_ERROR),
            new Row(NOT_CANCELLED, ANY, ANY, ANY, FAILING, TesState.SYSTEM_ERROR),
            new Row(NOT_CANCELLED, ENDED_TM, ANY, ANY, of(JobState.FAILED_PODS, JobState.RETRYING),
                    TesState.SYSTEM_ERROR),
            // Complete only if every executor completed; never from an earlier executor
            new Row(NOT_CANCELLED, of(JobState.SUCCEEDED), of(JobState.SUCCEEDED), of(JobState.SUCCEEDED), ANY,
                    TesState.COMPLETE),
            new Row(NOT_CANCELLED, of(JobState.SUCCEEDED), ANY, ANY, ANY, TesState.SYSTEM_ERROR),
            // Taskmaster still pending or running
            new Row(NOT_CANCELLED, of(JobState.PENDING), ANY, ANY, ANY, TesState.QUEUED),
            new Row(NOT_CANCELLED, of(JobState.RUNNING, JobState.RETRYING), of(JobState.ABSENT), of(JobState.ABSENT), ANY,
                    TesState.INITIALIZING),
            new Row(NOT_CANCELLED, of(JobState.RUNNING, JobState.RETRYING), ANY, of(JobState.PENDING), ANY,
                    TesState.QUEUED),
            new Row(NOT_CANCELLED, of(JobState.RUNNING, JobState.RETRYING), of(JobState.PENDING), of(JobState.ABSENT), ANY,
                    TesState.QUEUED),
            new Row(NOT_CANCELLED, of(JobState.RUNNING, JobState.RETRYING), ANY, ANY, ANY, TesState.RUNNING)
    );

    static Stream<Arguments> combinations() {
        List<Arguments> combinations = new ArrayList<>();
        for (boolean cancelled : new boolean[]{false, true}) {
            for (JobState taskmaster : TASKMASTER_STATES) {
                for (JobState executor0 : JobState.values()) {
                    for (JobState executor1 : JobState.values()) {
                        for (JobState filer : JobState.values()) {
                            combinations.add(Arguments.of(cancelled, taskmaster, executor0, executor1, filer));
                        }
                    }
                }
            }
        }
        return combinations.stream();
    }

    /**
     * The index of the first row of the table that matches.
     */
    private static int matchingRow(boolean cancelled, JobState taskmaster, JobState executor0,
                                   JobState executor1, JobState filer) {
        for (int i = 0; i < TABLE.size(); i++) {
            if (TABLE.get(i).matches(cancelled, taskmaster, executor0, executor1, filer)) return i;
        }
        throw new AssertionError("No row of the table matches");
    }

    @ParameterizedTest(name = "cancelled={0}, taskmaster={1}, executors={2},{3}, filer={4}")
    @MethodSource("combinations")
    void resolvesEveryCombination(boolean cancelled, JobState taskmaster, JobState executor0,
                                  JobState executor1, JobState filer) {
        Task task = task(cancelled, taskmaster, executor0, executor1, filer);
        assertThat(TaskStateResolver.resolve(task, () -> 2))
                .isEqualTo(TABLE.get(matchingRow(cancelled, taskmaster, executor0, executor1, filer)).expected);
    }

    @Test
    void everyRowOfTheTableIsReached() {
        Set<Integer> reached = new HashSet<>();
        combinations().forEach(arguments -> {
            Object[] a = arguments.get();
            reached.add(matchingRow((Boolean) a[0], (JobState) a[1], (JobState) a[2], (JobState) a[3],
                    (JobState) a[4]));
        });
        assertThat(reached).hasSize(TABLE.size());
    }

    /**
     * The last executor's job is missing (e.g., deleted): the task isn't
     * complete, whatever the state of the executors before it.
     */
    @Test
    void missingLastExecutorIsNotComplete() {
        Task task = task(false, JobState.SUCCEEDED, JobState.SUCCEEDED, JobState.ABSENT, JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.SYSTEM_ERROR);
    }

    /**
     * The first executor failed and the second one never ran: an executor error.
     */
    @Test
    void failedFirstExecutorIsExecutorError() {
        Task task = task(false, JobState.SUCCEEDED, JobState.FAILED, JobState.ABSENT, JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.EXECUTOR_ERROR);
    }

    /**
     * All executors and the outputs filer completed: complete.
     */
    @Test
    void allJobsCompletedIsComplete() {
        Task task = task(false, JobState.SUCCEEDED, JobState.SUCCEEDED, JobState.SUCCEEDED, JobState.SUCCEEDED);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.COMPLETE);
    }

    /**
     * The number of executors is read only once the taskmaster's job completed.
     */
    @Test
    void executorCountIsReadOnlyWhenNeeded() {
        Task task = task(false, JobState.RUNNING, JobState.RUNNING, JobState.ABSENT, JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> {
            throw new AssertionError("read the number of executors");
        })).isEqualTo(TesState.RUNNING);
    }

    /**
     * Without the number of executors (no task stored with the taskmaster),
     * the executor jobs that exist count as all executors.
     */
    @Test
    void unknownExecutorCountUsesExistingJobs() {
        Task task = task(false, JobState.SUCCEEDED, JobState.SUCCEEDED, JobState.ABSENT, JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(task, () -> null)).isEqualTo(TesState.COMPLETE);
        Task none = task(false, JobState.SUCCEEDED, JobState.ABSENT, JobState.ABSENT, JobState.ABSENT);
        assertThat(TaskStateResolver.resolve(none, () -> null)).isEqualTo(TesState.SYSTEM_ERROR);
    }

    /**
     * Conditions whose status isn't "True" don't count.
     */
    @Test
    void conditionsThatAreNotTrueDontCount() {
        Task task = task(false, JobState.RUNNING, JobState.RUNNING, JobState.ABSENT, JobState.ABSENT);
        task.getExecutors().get(0).getJob().getStatus()
                .addConditionsItem(new V1JobCondition().type("FailureTarget").status("False"));
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.RUNNING);
    }

    /**
     * Conditions other than failure ones don't make a job failed.
     */
    @Test
    void otherConditionsDontCount() {
        Task task = task(false, JobState.RUNNING, JobState.RUNNING, JobState.ABSENT, JobState.ABSENT);
        task.getExecutors().get(0).getJob().getStatus()
                .addConditionsItem(new V1JobCondition().type("Suspended").status("True"));
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.RUNNING);
    }

    /**
     * A pod without a status (just created) isn't pending.
     */
    @Test
    void podWithoutStatusIsNotPending() {
        Task task = task(false, JobState.RUNNING, JobState.RUNNING, JobState.ABSENT, JobState.ABSENT);
        task.getTaskmaster().getPods().get(0).setStatus(null);
        task.getExecutors().get(0).getPods().get(0).setStatus(null);
        assertThat(TaskStateResolver.resolve(task, () -> 2)).isEqualTo(TesState.RUNNING);
    }

    private static Task task(boolean cancelled, JobState taskmaster, JobState executor0, JobState executor1,
                             JobState filer) {
        Job taskmasterJob = job(TASK, taskmaster);
        if (cancelled) {
            taskmasterJob.getJob().getMetadata().putLabelsItem(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        }
        Task task = new Task(taskmasterJob);
        if (executor0 != JobState.ABSENT) task.addExecutor(job(TASK + "-ex-00", executor0));
        if (executor1 != JobState.ABSENT) task.addExecutor(job(TASK + "-ex-01", executor1));
        if (filer != JobState.ABSENT) task.setOutputFiler(job(TASK + JOB_NAME_FILER_SUF, filer));
        return task;
    }

    private static Job job(String name, JobState state) {
        V1JobStatus status = new V1JobStatus();
        String podPhase = null;
        switch (state) {
            case PENDING:
                status.active(1);
                podPhase = "Pending";
                break;
            case RUNNING:
                status.active(1);
                podPhase = "Running";
                break;
            case SUCCEEDED:
                status.succeeded(1).addConditionsItem(new V1JobCondition().type("Complete").status("True"));
                podPhase = "Succeeded";
                break;
            case FAILED:
                status.failed(1).addConditionsItem(new V1JobCondition().type("Failed").status("True"));
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
            default:
                throw new IllegalArgumentException(state.name());
        }
        Job job = new Job(new V1Job().metadata(new V1ObjectMeta().name(name)).status(status));
        job.addPod(new V1Pod().metadata(new V1ObjectMeta().name(name + "-pod")).spec(new V1PodSpec())
                .status(new V1PodStatus().phase(podPhase)));
        return job;
    }
}
