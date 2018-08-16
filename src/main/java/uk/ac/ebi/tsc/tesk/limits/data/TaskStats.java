package uk.ac.ebi.tsc.tesk.limits.data;

import com.google.common.collect.Lists;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author aniewielska
 * @since 13/08/2018
 */
public class TaskStats {

    private String taskName;
    private List<ExecutorStats> executors;
    private BigDecimal storageGB;

    private ResourceUsage getExecutorsUsage(Instant now) {
        return this.executors.stream().map(exec -> exec.getResourceUsage(now)).reduce(ResourceUsage.ZERO, ResourceUsage::add);
    }

    public ResourceUsage getResourceUsage(Instant now) {
        ResourceUsage executorsUsage = this.getExecutorsUsage(now);
        return new ResourceUsage(executorsUsage.getDurationHours(), executorsUsage.getCpuHours(),
                executorsUsage.getMemoryGBHours(), this.storageGB.multiply(executorsUsage.getDurationHours()));
    }

    public TaskResourceUsage toTaskResourceUsage(Instant now, boolean withExecutors) {
        return TaskResourceUsage.builder().
                taskName(taskName).
                storageGB(storageGB).
                resourceUsage(this.getResourceUsage(now)).
                executors(withExecutors ? this.executors.stream().map(exec -> exec.toExecutorResourceUsage(now)).collect(Collectors.toList()) : null).
                build();
    }

    public TaskStats(String taskName, BigDecimal storageGB, List<ExecutorStats> executors) {
        this.taskName = taskName;
        this.storageGB = storageGB;
        this.executors = Lists.newArrayList();
        this.executors.addAll(executors.stream().filter(ExecutorStats::isActiveOrEndedInRetrievalMonth).collect(Collectors.toList()));

    }

    public boolean hasExecutors() {
        return this.executors.size() > 0;
    }

}
