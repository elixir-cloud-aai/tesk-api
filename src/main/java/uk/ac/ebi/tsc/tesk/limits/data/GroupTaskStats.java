package uk.ac.ebi.tsc.tesk.limits.data;

import com.google.common.collect.Lists;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author aniewielska
 * @since 13/08/2018
 */
public class GroupTaskStats {

    private String groupName;
    private List<TaskStats> tasks;

    public GroupResourceUsage toGroupResourceUsage(Instant now, boolean withTasks, boolean withExecutors) {
        return GroupResourceUsage.builder().
                name(this.groupName).
                resourceUsage(this.getResourceUsage(now)).
                tasks(withTasks ? this.tasks.stream().map(task -> task.toTaskResourceUsage(now, withExecutors)).collect(Collectors.toList()) : null).
                build();
    }

    public GroupTaskStats(String groupName, List<TaskStats> tasks) {
        this.groupName = groupName;
        this.tasks = Lists.newArrayList();
        this.tasks.addAll(tasks.stream().filter(TaskStats::hasExecutors).collect(Collectors.toList()));
    }

    private ResourceUsage getResourceUsage(Instant now) {
        return this.tasks.stream().map(task -> task.getResourceUsage(now)).reduce(ResourceUsage.ZERO, ResourceUsage::add);
    }

}
