package uk.ac.ebi.tsc.tesk.tes.service;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_TESTASK_NAME_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.COMPLETED_STATES;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.JOB_CREATE_ATTEMPTS_NO;
import static uk.ac.ebi.tsc.tesk.k8s.constant.K8sConstants.K8S_BATCH_API_JOB_TYPE;
import static uk.ac.ebi.tsc.tesk.k8s.constant.K8sConstants.K8S_BATCH_API_VERSION;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1PodList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.*;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.exception.CancelNotRunningTask;
import uk.ac.ebi.tsc.tesk.tes.model.*;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;

/**
 * Implementation of controller endpoints. Orchestrates all kubernetes client calls (via wrapper)
 * and converting inputs and outputs with the help of the converter.
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
@Service
public class TesServiceImpl implements TesService {

    private static Logger logger = LoggerFactory.getLogger(TesServiceImpl.class);

    private final KubernetesClientWrapper kubernetesClientWrapper;

    private final TesKubernetesConverter converter;

    public TesServiceImpl(
            KubernetesClientWrapper kubernetesClientWrapper, TesKubernetesConverter converter) {
        this.kubernetesClientWrapper = kubernetesClientWrapper;
        this.converter = converter;
    }

    /**
     * Creates new TES task, by converting input and calling create method. In case of detecting
     * duplicated task ID, retries with new generated ID (up to a limit of retries)
     */
    @Override
    public TesCreateTaskResponse createTask(TesTask task, User user) {

        int attemptsNo = 0;
        while (true) {
            try {

                TesResources resources = task.getResources();

                if (resources != null && resources.getRamGb() != null) {
                    Double minimumRamGb = this.kubernetesClientWrapper.minimumRamGb();
                    if (resources.getRamGb() < minimumRamGb) {
                        resources.setRamGb(minimumRamGb);
                        task.setResources(resources);
                    }
                }

                V1Job taskMasterJob = this.converter.fromTesTaskToK8sJob(task, user);
                V1ConfigMap taskMasterConfigMap =
                        this.converter.fromTesTaskToK8sConfigMap(task, user, taskMasterJob);
                // fails before the job exists, e.g. for a ConfigMap that is too large, a name that
                // is taken, or no right to set the owner: the dry run has an owner reference with
                // a placeholder UID, which Kubernetes doesn't store
                setOwner(taskMasterConfigMap, taskMasterJob, UUID.randomUUID().toString());
                this.kubernetesClientWrapper.checkConfigMap(taskMasterConfigMap);
                V1Job createdJob = this.kubernetesClientWrapper.createJob(taskMasterJob);
                this.createOwnedConfigMap(taskMasterConfigMap, createdJob);
                return this.converter.fromK8sJobToTesCreateTaskResponse(createdJob);
            } catch (KubernetesException e) {
                // in case of job name collision retry converting task to job (with new generated
                // name) and creating the job
                if (!e.isObjectNameDuplicated() || ++attemptsNo >= JOB_CREATE_ATTEMPTS_NO) {
                    throw e;
                }
            } catch (Exception exc) {
                logger.error("ERROR: In createTask", exc);
                logger.error(exc.getMessage());
                throw exc;
            }
        }
    }

    /** Makes the job, with the given UID, the only owner of the ConfigMap. */
    private static void setOwner(V1ConfigMap configMap, V1Job job, String uid) {
        configMap
                .getMetadata()
                .setOwnerReferences(
                        Collections.singletonList(
                                new V1OwnerReference()
                                        .apiVersion(K8S_BATCH_API_VERSION)
                                        .kind(K8S_BATCH_API_JOB_TYPE)
                                        .name(job.getMetadata().getName())
                                        .uid(uid)));
    }

    /**
     * Creates the taskmaster's ConfigMap, owned by its job, so that Kubernetes deletes the
     * ConfigMap with the job (e.g. after the job's TTL). The job's UID is known only once the job
     * exists, so the job is created first (after a dry run of the ConfigMap); its pod waits until
     * the ConfigMap it mounts exists. If the ConfigMap can't be created, the job is deleted, so
     * that its pod doesn't wait forever, and the ConfigMap's error is passed on (a name collision
     * then creates the task again). If the job can't be deleted either, an error that isn't a
     * {@link KubernetesException} is passed on instead (cause: the delete's error), so that the
     * task isn't created again.
     *
     * <p>The owner reference doesn't set {@code blockOwnerDeletion}, which would need the right to
     * update the job's finalizers.
     */
    private void createOwnedConfigMap(V1ConfigMap configMap, V1Job job) {
        setOwner(configMap, job, job.getMetadata().getUid());
        try {
            this.kubernetesClientWrapper.createConfigMap(configMap);
        } catch (RuntimeException e) {
            try {
                this.kubernetesClientWrapper.deleteJob(job.getMetadata().getName());
            } catch (RuntimeException deleteError) {
                // the job stays: no retry, which could leave more jobs behind, or let this one
                // run with another task's ConfigMap of the same name
                logger.error(
                        "Creating the ConfigMap of job {} failed ({}), and deleting the job"
                                + " failed, too",
                        job.getMetadata().getName(),
                        e.getMessage(),
                        deleteError);
                // not a KubernetesException, so that a 409 of the delete isn't taken for a name
                // collision, which would create the task again
                IllegalStateException error =
                        new IllegalStateException(
                                "Creating the ConfigMap of a task failed, and deleting its job"
                                        + " failed, too",
                                deleteError);
                error.addSuppressed(e);
                throw error;
            }
            throw e;
        }
    }

    /**
     * Gets single task's details based on ID. Performs a series of kubernetes API calls and
     * converts results with means of the converter.
     *
     * @param taskId - TES task ID (==taskmaster's job name)
     * @param view - one of {@link TaskView} values, decides on how much detail is put in results
     * @return - TES task details
     */
    @Override
    public TesTask getTask(String taskId, TaskView view, User user) {
        return this.getTask(this.readTask(taskId), view, false);
    }

    /**
     * Reads all Kubernetes objects of a single task: the taskmaster's job and pods, the executors'
     * jobs and pods, and the outputs filer's job.
     */
    private Task readTask(String taskId) {
        V1Job taskMasterJob = this.kubernetesClientWrapper.readTaskmasterJob(taskId);
        V1JobList executorJobs =
                this.kubernetesClientWrapper.listSingleTaskExecutorJobs(
                        taskMasterJob.getMetadata().getName());
        V1PodList taskMasterPods = this.kubernetesClientWrapper.listSingleJobPods(taskMasterJob);
        TaskBuilder taskBuilder =
                TaskBuilder.newSingleTask()
                        .addJob(taskMasterJob)
                        .addJobList(executorJobs.getItems())
                        .addPodList(taskMasterPods.getItems());
        for (V1Job executorJob : executorJobs.getItems()) {
            V1PodList executorJobPods = this.kubernetesClientWrapper.listSingleJobPods(executorJob);
            taskBuilder.addPodList(executorJobPods.getItems());
        }
        Optional<V1Job> outputFilerJob =
                this.kubernetesClientWrapper.getSingleTaskOutputFilerJob(
                        taskMasterJob.getMetadata().getName());
        outputFilerJob.ifPresent(taskBuilder::addJob);
        return taskBuilder.getTask();
    }

    /** Common part of task's details retrieval for both single task and each of a list of tasks */
    private TesTask getTask(Task taskObjects, TaskView view, boolean isList) {

        if (view == TaskView.MINIMAL) {
            return this.converter.fromK8sJobsToTesTaskMinimal(taskObjects, isList);
        }

        TesTask task =
                this.converter.fromK8sJobsToTesTaskBasic(taskObjects, view == TaskView.BASIC);

        if (view == TaskView.BASIC) {
            return task;
        }

        for (int i = 0; i < taskObjects.getExecutors().size(); i++) {
            Job executorJob = taskObjects.getExecutors().get(i);
            if (executorJob.hasPods()) {
                TesExecutorLog executorLog = task.getLogs().get(0).getLogs().get(i);
                if (view == TaskView.FULL) {
                    String executorPodLog =
                            this.kubernetesClientWrapper.readPodLog(
                                    executorJob.getFirstPod().getMetadata().getName());
                    if (executorPodLog
                            != null) { // to ensure compatibility with py-tes -- keep stdout
                        // non-null
                        executorLog.setStdout(executorPodLog);
                    }
                }
            }
        }

        if (taskObjects.getTaskmaster().hasPods()) {
            String taskMasterPodLog =
                    this.kubernetesClientWrapper.readPodLog(
                            taskObjects.getTaskmaster().getFirstPod().getMetadata().getName());
            if (taskMasterPodLog != null) {
                // to ensure compatibility with py-tes, which does not like the array of nulls
                task.getLogs().get(0).addSystemLogsItem(taskMasterPodLog);
            }
        }
        return task;
    }

    /**
     * Gets a full list of tasks. Performs Kubernetes API batch calls (all taskmasters, all
     * executors, all pods), combines them together into valid {@link Task} objects and converts to
     * result with means of the converter.
     *
     * <p>Kubernetes can't select jobs by a prefix of an annotation, so the tasks are filtered by
     * name after Kubernetes returned a page of taskmaster jobs: a page can be shorter than {@code
     * pageSize}, even empty, while {@code next_page_token} still leads to the next page.
     *
     * @param namePrefix - only tasks whose names start with it (case-sensitive); all tasks if null
     *     or empty
     * @param pageSize - maximum number of taskmaster jobs read for the page
     * @param pageToken - continue token of Kubernetes, from the previous page's next_page_token
     * @param view - one of {@link TaskView} values, decides on how much detail is put in each
     *     resulting task
     * @return - the page of tasks, with the continue token of the next page, if there is one
     */
    @Override
    public TesListTasksResponse listTasks(
            String namePrefix, Long pageSize, String pageToken, TaskView view, User user) {

        V1JobList taskmasterJobs =
                this.kubernetesClientWrapper.listAllTaskmasterJobsForUser(
                        pageToken,
                        Optional.ofNullable(pageSize).map(Long::intValue).orElse(null),
                        user);
        String nextPageToken = taskmasterJobs.getMetadata().getContinue();
        List<V1Job> matchingTaskmasterJobs =
                taskmasterJobs.getItems().stream()
                        .filter(job -> hasNamePrefix(job, namePrefix))
                        .collect(Collectors.toList());
        if (matchingTaskmasterJobs.isEmpty()) {
            // nothing to combine: spare the lists of all executors, filers and pods
            return new TesListTasksResponse().tasks(new ArrayList<>()).nextPageToken(nextPageToken);
        }
        V1JobList executorJobs = this.kubernetesClientWrapper.listAllTaskExecutorJobs();
        V1JobList filerJobs = this.kubernetesClientWrapper.listAllFilerJobs();
        V1PodList jobPods = this.kubernetesClientWrapper.listAllJobPods();
        TaskBuilder taskListBuilder =
                TaskBuilder.newTaskList()
                        .addJobList(matchingTaskmasterJobs)
                        .addJobList(executorJobs.getItems())
                        .addJobList(filerJobs.getItems())
                        .addPodList(jobPods.getItems());
        List<TesTask> tasks =
                taskListBuilder.getTaskList().stream()
                        .map(task -> this.getTask(task, view, true))
                        .collect(Collectors.toList());
        return new TesListTasksResponse().tasks(tasks).nextPageToken(nextPageToken);
    }

    /**
     * Whether the name of a taskmaster job's task (annotation {@code tes-task-name}) starts with
     * the prefix. Every task matches a null or empty prefix; a task without a name matches no
     * other.
     */
    private static boolean hasNamePrefix(V1Job taskmasterJob, String namePrefix) {
        if (namePrefix == null || namePrefix.isEmpty()) {
            return true;
        }
        String name =
                Optional.ofNullable(taskmasterJob.getMetadata().getAnnotations())
                        .map(annotations -> annotations.get(ANN_TESTASK_NAME_KEY))
                        .orElse(null);
        return name != null && name.startsWith(namePrefix);
    }

    /**
     * Cancels a task with a given id, by setting a label to taskmaster Job and Pod object. If not
     * task with a given Id - throws TaskNotFoundException If task completed, throws
     * CancelNotRunningTask
     *
     * @param taskId - TES task ID (==taskmaster's job name)
     */
    @Override
    public void cancelTask(String taskId) {
        // the state of all the task's jobs, as for getTask: a task that ended (e.g. an executor
        // failed) isn't cancelled
        Task task = this.readTask(taskId);
        TesState state = this.converter.extractStateFromK8sJobs(task);
        if (COMPLETED_STATES.contains(state)) {
            throw new CancelNotRunningTask(taskId);
        }
        this.kubernetesClientWrapper.labelJobAsCancelled(taskId);
        V1PodList taskMasterPods = new V1PodList().items(task.getTaskmaster().getPods());
        this.converter
                .getNameOfFirstRunningPod(taskMasterPods)
                .ifPresent(this.kubernetesClientWrapper::labelPodAsCancelled);
    }
}
