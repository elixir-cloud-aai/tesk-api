package uk.ac.ebi.tsc.tesk.limits.service;

import io.kubernetes.client.models.V1JobList;
import io.kubernetes.client.models.V1PodList;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import uk.ac.ebi.tsc.tesk.limits.data.GroupTaskStats;
import uk.ac.ebi.tsc.tesk.limits.util.K8sStatsConverter;
import uk.ac.ebi.tsc.tesk.util.component.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.util.data.TaskBuilder;

import java.time.Instant;

/**
 * @author aniewielska
 * @since 13/08/2018
 */
@Service
public class UsageService {

    private final KubernetesClientWrapper kubernetesClient;

    private final K8sStatsConverter converter;

    public UsageService(KubernetesClientWrapper kubernetesClient, K8sStatsConverter converter) {
        this.kubernetesClient = kubernetesClient;
        this.converter = converter;

    }

    //@Cacheable -TODO <-- think of caching results of this query
    public GroupTaskStats getTaskStatisticsForUserOrGroup(String name, boolean isGroup) {

        V1JobList taskmasterJobs = this.kubernetesClient.listAllTaskmasterJobsForUserOrGroup(name, isGroup);
        V1JobList executorJobs = this.kubernetesClient.listAllTaskExecutorJobs();
        V1PodList jobPods = this.kubernetesClient.listAllJobPods();
        TaskBuilder taskListBuilder = TaskBuilder.newTaskList().
                addJobList(taskmasterJobs.getItems()).
                addJobList(executorJobs.getItems()).
                addPodList(jobPods.getItems());
        return converter.convertTaskList(name, taskListBuilder.getTaskList(), Instant.now());
    }
}
