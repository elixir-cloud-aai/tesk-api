package uk.ac.ebi.tsc.tesk.limits.util;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodStatus;
import io.kubernetes.client.models.V1ResourceRequirements;
import org.springframework.stereotype.Component;
import uk.ac.ebi.tsc.tesk.limits.data.ExecutorStats;
import uk.ac.ebi.tsc.tesk.limits.data.GroupTaskStats;
import uk.ac.ebi.tsc.tesk.limits.data.TaskStats;
import uk.ac.ebi.tsc.tesk.util.data.Job;
import uk.ac.ebi.tsc.tesk.util.data.Task;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static uk.ac.ebi.tsc.tesk.util.constant.K8sConstants.RESOURCE_CPU_KEY;
import static uk.ac.ebi.tsc.tesk.util.constant.K8sConstants.RESOURCE_MEM_KEY;

/**
 * @author aniewielska
 * @since 13/08/2018
 */
@Component
public class K8sStatsConverter {

    public GroupTaskStats convertTaskList(String groupName, List<Task> tasks, Instant retrievalTime) {

        List<TaskStats> taskStatsList = tasks.stream().map(task -> this.convertTask(task, retrievalTime)).collect(Collectors.toList());
        return new GroupTaskStats(groupName, taskStatsList);
    }

    public TaskStats convertTask(Task task, Instant retrievalTime) {

        List<ExecutorStats> executors = task.getExecutors().stream().map(executor -> this.convertExecutor(executor, retrievalTime)).collect(Collectors.toList());
        return new TaskStats(task.getTaskmaster().getJobName(), BigDecimal.ZERO, executors);
    }

    public ExecutorStats convertExecutor(Job executor, Instant retrievalTime) {
        Instant startTime = Optional.ofNullable(executor.getFirstPod()).
                map(V1Pod::getStatus).
                map(V1PodStatus::getStartTime).
                map(time -> Instant.ofEpochMilli(time.getMillis())).
                orElse(null);
        Instant endTime = Optional.ofNullable(executor.getJob().getStatus().getCompletionTime()).map(time -> Instant.ofEpochMilli(time.getMillis())).
                orElse(null);
        V1ResourceRequirements requirements = executor.getJob().getSpec().getTemplate().getSpec().getContainers().get(0).getResources();
        BigDecimal cpuRequest = Optional.ofNullable(requirements.getRequests()).map(req -> req.get(RESOURCE_CPU_KEY)).map(Quantity::getNumber).orElse(BigDecimal.ZERO);
        BigDecimal memRequest = Optional.ofNullable(requirements.getRequests()).map(req -> req.get(RESOURCE_MEM_KEY)).map(Quantity::getNumber).orElse(BigDecimal.ZERO);

        return ExecutorStats.builder().
                cpu(cpuRequest).
                memoryGB(memRequest).
                startDateTime(startTime).
                endDateTime(endTime).
                retrievalTime(retrievalTime).
                build();
    }
}
