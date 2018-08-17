package uk.ac.ebi.tsc.tesk.limits.controller;

import org.springframework.web.bind.annotation.*;
import uk.ac.ebi.tsc.tesk.common.data.TaskView;
import uk.ac.ebi.tsc.tesk.common.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.limits.data.GroupResourceUsage;
import uk.ac.ebi.tsc.tesk.limits.data.TaskResourceUsage;
import uk.ac.ebi.tsc.tesk.limits.service.UsageService;

import java.time.Instant;
import java.util.List;

/**
 * @author aniewielska
 * @since 13/08/2018
 */
@RestController
@RequestMapping("/limits")
public class ResourceLimitsController {

    private final UsageService usageService;

    public ResourceLimitsController(UsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping(value = "/groups/{groupName}")
    public GroupResourceUsage getResourceUsageForGroup(@PathVariable("groupName") String groupName, @RequestParam(name = "view", required = false, defaultValue = "MINIMAL") TaskView view) {
        return this.usageService.getTaskStatisticsForGroup(groupName).toGroupResourceUsage(Instant.now(), view != TaskView.MINIMAL, view == TaskView.FULL);
    }

    @GetMapping(value = "/users/{userId}")
    public GroupResourceUsage getResourceUsageForUser(@PathVariable("userId") String userId, @RequestParam(name = "view", required = false, defaultValue = "MINIMAL") TaskView view) {
        return this.usageService.getTaskStatisticsForUser(userId).toGroupResourceUsage(Instant.now(), view != TaskView.MINIMAL, view == TaskView.FULL);
    }

    @GetMapping(value = "/users/{userId}/tasks")
    public List<TaskResourceUsage> getUserTasks(@PathVariable("userId") String userId, @RequestParam(name = "view", required = false, defaultValue = "MINIMAL") TaskView view) {
        GroupResourceUsage groupUsage = this.usageService.getTaskStatisticsForUser(userId).toGroupResourceUsage(Instant.now(), true, view == TaskView.FULL);
        return groupUsage.getTasks();
    }

    @GetMapping(value = "/users/{userId}/tasks/{taskId}")
    public TaskResourceUsage getUserTask(@PathVariable("userId") String userId, @PathVariable("taskId") String taskId, @RequestParam(name = "view", required = false, defaultValue = "MINIMAL") TaskView view) {
        GroupResourceUsage groupUsage = this.usageService.getTaskStatisticsForUser(userId).toGroupResourceUsage(Instant.now(), true, view == TaskView.FULL);
        return groupUsage.getTasks().stream().filter(task -> task.getTaskName().equals(taskId)).findFirst().orElseThrow(() -> new TaskNotFoundException(taskId));
    }
}
