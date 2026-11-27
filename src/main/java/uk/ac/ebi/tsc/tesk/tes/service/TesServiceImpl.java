package uk.ac.ebi.tsc.tesk.tes.service;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_TESTASK_NAME_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.COMPLETED_STATES;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.JOB_CREATE_ATTEMPTS_NO;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TESTASK_ID_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.K8sConstants.K8S_BATCH_API_JOB_TYPE;
import static uk.ac.ebi.tsc.tesk.k8s.constant.K8sConstants.K8S_BATCH_API_VERSION;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Secret;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.constant.K8sConstants.PodPhase;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;
import uk.ac.ebi.tsc.tesk.k8s.convert.IgnoredBackendParameters;
import uk.ac.ebi.tsc.tesk.k8s.convert.TaskStateResolver;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.*;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.exception.CancelNotRunningTask;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.tes.model.*;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;

/**
 * Implementation of controller endpoints. Orchestrates all kubernetes client calls (via wrapper)
 * and converting inputs and outputs with the help of the converter.
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@Service
public class TesServiceImpl implements TesService {

    private static Logger logger = LoggerFactory.getLogger(TesServiceImpl.class);

    private final KubernetesClientWrapper kubernetesClientWrapper;

    private final TesKubernetesConverter converter;

    private final TaskAccess access;

    /**
     * @param access who may get, list and cancel which tasks: with authentication, the decision
     *     service for the operations switched on ({@code taskAccess}); without (noauth), there is
     *     none, and the owner rule decides
     */
    @Autowired
    public TesServiceImpl(
            KubernetesClientWrapper kubernetesClientWrapper,
            TesKubernetesConverter converter,
            ObjectProvider<TaskAccess> access) {
        this(
                kubernetesClientWrapper,
                converter,
                access.getIfAvailable(() -> TaskAccess.OWNER_ONLY));
    }

    TesServiceImpl(
            KubernetesClientWrapper kubernetesClientWrapper,
            TesKubernetesConverter converter,
            TaskAccess access) {
        this.kubernetesClientWrapper = kubernetesClientWrapper;
        this.converter = converter;
        this.access = access;
    }

    /**
     * Creates new TES task, by converting input and calling create method. In case of detecting
     * duplicated task ID, retries with new generated ID (up to a limit of retries)
     */
    @Override
    public TesCreateTaskResponse createTask(TesTask task, User user) {

        // TESK supports no backend parameters: they are neither stored nor returned (TES 1.1)
        List<String> ignoredBackendParameters = IgnoredBackendParameters.removeFrom(task);
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
                IgnoredBackendParameters.annotate(
                        taskMasterJob.getMetadata(), ignoredBackendParameters);
                V1ConfigMap taskMasterConfigMap =
                        this.converter.fromTesTaskToK8sConfigMap(task, user, taskMasterJob);
                List<V1Secret> imagePullSecrets =
                        this.converter.fromTesTaskToK8sImagePullSecrets(task, taskMasterJob);
                // fails before the job exists, e.g. for a ConfigMap that is too large, a name that
                // is taken, or no right to set the owner: the dry run has an owner reference with
                // a placeholder UID, which Kubernetes doesn't store
                String placeholderUid = UUID.randomUUID().toString();
                setOwner(taskMasterConfigMap.getMetadata(), taskMasterJob, placeholderUid);
                this.kubernetesClientWrapper.checkConfigMap(taskMasterConfigMap);
                for (V1Secret secret : imagePullSecrets) {
                    setOwner(secret.getMetadata(), taskMasterJob, placeholderUid);
                    this.kubernetesClientWrapper.checkSecret(secret);
                }
                V1Job createdJob = this.kubernetesClientWrapper.createJob(taskMasterJob);
                this.createOwnedResources(taskMasterConfigMap, imagePullSecrets, createdJob);
                // only for tasks that exist: a failed create leaves no task to warn about
                if (!ignoredBackendParameters.isEmpty()) {
                    logger.warn(
                            "Task {}: {}",
                            createdJob.getMetadata().getName(),
                            IgnoredBackendParameters.message(ignoredBackendParameters));
                }
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

    /** Makes the job, with the given UID, the only owner of a task resource. */
    private static void setOwner(V1ObjectMeta metadata, V1Job job, String uid) {
        metadata.setOwnerReferences(
                Collections.singletonList(
                        new V1OwnerReference()
                                .apiVersion(K8S_BATCH_API_VERSION)
                                .kind(K8S_BATCH_API_JOB_TYPE)
                                .name(job.getMetadata().getName())
                                .uid(uid)));
    }

    /**
     * Creates the taskmaster's image pull Secrets and ConfigMap, owned by its job, so that
     * Kubernetes deletes them with the job. The job's UID is known only once it exists, so the job
     * is created first (after dry runs); its pod waits for the ConfigMap. Secrets are created
     * before the ConfigMap, so the taskmaster cannot start executor jobs before their pull secrets
     * exist. If creating a resource fails, the job is deleted. If deleting it fails too, an error
     * that isn't a {@link KubernetesException} stops retries.
     *
     * <p>The owner reference doesn't set {@code blockOwnerDeletion}, which would need the right to
     * update the job's finalizers.
     */
    private void createOwnedResources(V1ConfigMap configMap, List<V1Secret> secrets, V1Job job) {
        setOwner(configMap.getMetadata(), job, job.getMetadata().getUid());
        try {
            for (V1Secret secret : secrets) {
                setOwner(secret.getMetadata(), job, job.getMetadata().getUid());
                this.kubernetesClientWrapper.createSecret(secret);
            }
            this.kubernetesClientWrapper.createConfigMap(configMap);
        } catch (RuntimeException e) {
            try {
                this.kubernetesClientWrapper.deleteJob(job.getMetadata().getName());
            } catch (RuntimeException deleteError) {
                // the job stays: no retry, which could leave more jobs behind, or let this one
                // run with another task's ConfigMap of the same name
                logger.error(
                        "Creating a resource of job {} failed ({}), and deleting the job"
                                + " failed, too",
                        job.getMetadata().getName(),
                        e.getMessage(),
                        deleteError);
                // not a KubernetesException, so that a 409 of the delete isn't taken for a name
                // collision, which would create the task again
                IllegalStateException error =
                        new IllegalStateException(
                                "Creating a resource of a task failed, and deleting its job"
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
        if (!this.access.decides(TaskAccess.Operation.GET)) {
            return this.getTask(this.readTask(taskId, user), view);
        }
        // the whole task is read before the decision, whoever owns it: the decision service gets
        // its state, which only all its jobs and pods tell (as tes-authz's spec, section 3.3, says)
        Optional<Task> task = this.readOwnedTask(taskId);
        // asked for missing tasks too, so that the answer doesn't tell whether a task exists
        if (!this.access.mayGet(user, taskId, task.map(this::facts).orElse(null), view)
                || task.isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
        return this.getTask(task.get(), view);
    }

    /**
     * Reads all Kubernetes objects of a single task of the user: the taskmaster's job and pods, the
     * executors' jobs and pods, and the outputs filer's job.
     *
     * @throws TaskNotFoundException if there is no such task, or another user created it: both
     *     after the same single request (the taskmaster's job), so that the answer doesn't tell
     *     whether another user's task exists
     */
    private Task readTask(String taskId, User user) {
        V1Job taskMasterJob = this.kubernetesClientWrapper.readTaskmasterJob(taskId);
        if (!user.isCreatorOf(taskMasterJob.getMetadata())) {
            throw new TaskNotFoundException(taskId);
        }
        return this.readTask(taskMasterJob);
    }

    /**
     * Reads all Kubernetes objects of a single task with an exact owner, whoever it is, for a
     * decision service
     *
     * @return the task, or empty if there is no such task, or it has no exact owner (which counts
     *     as missing)
     */
    private Optional<Task> readOwnedTask(String taskId) {
        V1Job taskMasterJob;
        try {
            taskMasterJob = this.kubernetesClientWrapper.readTaskmasterJob(taskId);
        } catch (TaskNotFoundException e) {
            return Optional.empty();
        }
        if (CreatorLabels.creatorId(taskMasterJob.getMetadata()) == null) {
            return Optional.empty();
        }
        return Optional.of(this.readTask(taskMasterJob));
    }

    /** The facts of a task that a decision service gets: its owner, state and tags */
    private TaskAccess.TaskFacts facts(Task task) {
        V1Job taskMasterJob = task.getTaskmaster().getJob();
        return new TaskAccess.TaskFacts(
                CreatorLabels.creatorId(taskMasterJob.getMetadata()),
                this.converter.extractStateFromK8sJobs(task),
                this.converter.extractTags(taskMasterJob));
    }

    /** Reads the other Kubernetes objects of the task of a taskmaster's job */
    private Task readTask(V1Job taskMasterJob) {
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
        if (outputFilerJob.isPresent()) {
            taskBuilder.addJob(outputFilerJob.get());
            if (TaskStateResolver.isLabelledCancelled(taskMasterJob.getMetadata())) {
                // its pods tell whether a cancelled task is still canceling, the only rule of
                // TaskStateResolver that needs them; the list path reads all pods (issue #144)
                taskBuilder.addPodList(
                        this.kubernetesClientWrapper
                                .listSingleJobPods(outputFilerJob.get())
                                .getItems());
            }
        }
        return taskBuilder.getTask();
    }

    /** Common part of task's details retrieval for both single task and each of a list of tasks */
    private TesTask getTask(Task taskObjects, TaskView view) {

        if (view == TaskView.MINIMAL) {
            return this.converter.fromK8sJobsToTesTaskMinimal(taskObjects);
        }

        TesTask task =
                this.converter.fromK8sJobsToTesTaskBasic(taskObjects, view == TaskView.BASIC);

        if (view == TaskView.BASIC) {
            return task;
        }

        // the view is FULL: the executors' logs
        for (int i = 0; i < taskObjects.getExecutors().size(); i++) {
            Job executorJob = taskObjects.getExecutors().get(i);
            if (executorJob.hasPods()) {
                TesExecutorLog executorLog = task.getLogs().get(0).getLogs().get(i);
                String executorPodLog =
                        this.kubernetesClientWrapper.readPodLog(
                                executorJob.getFirstPod().getMetadata().getName());
                // to ensure compatibility with py-tes -- keep stdout non-null
                if (executorPodLog != null) {
                    executorLog.setStdout(executorPodLog);
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
        // after the taskmaster's log, which clients may expect first
        IgnoredBackendParameters.systemLog(taskObjects.getTaskmaster().getJob().getMetadata())
                .ifPresent(task.getLogs().get(0)::addSystemLogsItem);
        return task;
    }

    /**
     * Gets a full list of tasks. Performs Kubernetes API batch calls (all taskmasters, all
     * executors, all pods), combines them together into valid {@link Task} objects and converts to
     * result with means of the converter.
     *
     * <p>Kubernetes can't select jobs by a prefix of an annotation, by the tags of the task stored
     * with the job, or by the state, which TESK derives from all the task's jobs. So the tasks are
     * filtered after Kubernetes returned a page of taskmaster jobs: by name and tags first, by
     * state once the other jobs are read. A page can be shorter than {@code pageSize}, even empty,
     * while {@code next_page_token} still leads to the next page.
     *
     * @param filter - only tasks that match it (name prefix, state and tags)
     * @param pageSize - maximum number of taskmaster jobs read for the page
     * @param pageToken - continue token of Kubernetes, from the previous page's next_page_token
     * @param view - one of {@link TaskView} values, decides on how much detail is put in each
     *     resulting task
     * @return - the page of tasks, with the continue token of the next page, if there is one
     */
    @Override
    public TesListTasksResponse listTasks(
            TaskFilter filter, Long pageSize, String pageToken, TaskView view, User user) {

        TaskAccess.ListScope scope =
                this.access.decides(TaskAccess.Operation.LIST)
                        ? this.access.listScope(user, view)
                        : TaskAccess.ListScope.OWN;
        if (scope == TaskAccess.ListScope.NONE) {
            // nothing the caller may see: no Kubernetes request
            return new TesListTasksResponse().tasks(new ArrayList<>());
        }
        boolean all = scope == TaskAccess.ListScope.ALL;
        String continueToken = PageTokens.continueToken(pageToken, all);
        Integer limit = Optional.ofNullable(pageSize).map(Long::intValue).orElse(null);
        V1JobList taskmasterJobs =
                all
                        ? this.kubernetesClientWrapper.listAllOwnedTaskmasterJobs(
                                continueToken, limit)
                        : this.kubernetesClientWrapper.listAllTaskmasterJobsForUser(
                                continueToken, limit, user);
        String nextPageToken =
                PageTokens.pageToken(taskmasterJobs.getMetadata().getContinue(), all);
        List<V1Job> matchingTaskmasterJobs =
                taskmasterJobs.getItems().stream()
                        .filter(job -> filter.matchesName(taskName(job)))
                        .filter(job ->
                                !filter.hasTags()
                                        || filter.matchesTags(this.converter.extractTags(job)))
                        .collect(Collectors.toList());
        if (matchingTaskmasterJobs.isEmpty()) {
            // nothing to combine: spare the lists of all executors, filers and pods
            return new TesListTasksResponse().tasks(new ArrayList<>()).nextPageToken(nextPageToken);
        }
        V1JobList executorJobs = this.kubernetesClientWrapper.listAllTaskExecutorJobs();
        List<V1Job> filerJobs = this.listFilerJobs(matchingTaskmasterJobs);
        V1PodList jobPods = this.kubernetesClientWrapper.listAllJobPods();
        TaskBuilder taskListBuilder =
                TaskBuilder.newTaskList()
                        .addJobList(matchingTaskmasterJobs)
                        .addJobList(executorJobs.getItems())
                        .addJobList(filerJobs)
                        .addPodList(jobPods.getItems());
        List<TesTask> tasks =
                taskListBuilder.getTaskList().stream()
                        // the state is derived only if the filter needs it
                        .filter(task ->
                                !filter.hasState()
                                        || filter.matchesState(
                                                this.converter.extractStateFromK8sJobs(task)))
                        .map(task -> this.getTask(task, view))
                        .collect(Collectors.toList());
        return new TesListTasksResponse().tasks(tasks).nextPageToken(nextPageToken);
    }

    /**
     * The filer jobs of the tasks of these taskmaster jobs: selected by their label {@code
     * taskmaster-name}, which taskmasters with tesk-core#147 put on them (issue #177). The filer
     * jobs of older taskmasters have no label: if a task that may have filer jobs (its stored task
     * has inputs, outputs or volumes, or can't be read) has no labelled filer job, all unlabelled
     * filer jobs are listed as well. Tasks with a labelled filer job, e.g. the inputs filer's, need
     * no fallback, as a taskmaster labels all its filer jobs or none.
     */
    private List<V1Job> listFilerJobs(List<V1Job> taskmasterJobs) {
        List<String> taskIds =
                taskmasterJobs.stream()
                        .map(job -> job.getMetadata().getName())
                        .collect(Collectors.toList());
        List<V1Job> filerJobs =
                new ArrayList<>(
                        this.kubernetesClientWrapper.listFilerJobsOfTasks(taskIds).getItems());
        Set<String> tasksWithLabelledFilers =
                filerJobs.stream()
                        .map(job -> job.getMetadata().getLabels())
                        .filter(Objects::nonNull)
                        .map(labels -> labels.get(LABEL_TESTASK_ID_KEY))
                        .collect(Collectors.toSet());
        boolean fallback =
                taskmasterJobs.stream()
                        .filter(job ->
                                !tasksWithLabelledFilers.contains(job.getMetadata().getName()))
                        .anyMatch(this.converter::mayHaveFilerJobs);
        if (fallback) {
            filerJobs.addAll(this.kubernetesClientWrapper.listUnlabelledFilerJobs().getItems());
        }
        return filerJobs;
    }

    /** The name of a taskmaster job's task (annotation {@code tes-task-name}), or null. */
    private static String taskName(V1Job taskmasterJob) {
        return Optional.ofNullable(taskmasterJob.getMetadata().getAnnotations())
                .map(annotations -> annotations.get(ANN_TESTASK_NAME_KEY))
                .orElse(null);
    }

    /**
     * Cancels a task with a given id, by setting a label to taskmaster Job and Pod object. If not
     * task with a given Id - throws TaskNotFoundException If task completed, throws
     * CancelNotRunningTask
     *
     * @param taskId - TES task ID (==taskmaster's job name)
     * @param user - the caller; other users' tasks are not found
     */
    @Override
    public void cancelTask(String taskId, User user) {
        // the state of all the task's jobs, as for getTask: a task that ended (e.g. an executor
        // failed) isn't cancelled
        Task task =
                this.access.decides(TaskAccess.Operation.CANCEL)
                        ? this.readTaskToCancel(taskId, user)
                        : this.readTask(taskId, user);
        TesState state = this.converter.extractStateFromK8sJobs(task);
        if (COMPLETED_STATES.contains(state)) {
            throw new CancelNotRunningTask(taskId);
        }
        if (state == TesState.CANCELING) {
            // cancelled already, its pods are still stopping (issue #144); a pending or running
            // taskmaster pod without the label (e.g. labelling it failed, or Kubernetes
            // restarted the taskmaster) gets it now, as only the pod's label stops the
            // taskmaster; nothing else is patched again
            task.getTaskmaster().getPods().stream()
                    .filter(TesServiceImpl::isStartedAndNotLabelledCancelled)
                    .forEach(
                            pod -> this.labelPodAsCancelledUnlessGone(pod.getMetadata().getName()));
            return;
        }
        V1PodList taskMasterPods = new V1PodList().items(task.getTaskmaster().getPods());
        Optional<String> runningPod = this.converter.getNameOfFirstRunningPod(taskMasterPods);
        if (runningPod.isPresent()) {
            // the taskmaster sees the pod's label and stops the task
            this.kubernetesClientWrapper.labelJobAsCancelled(taskId);
            this.kubernetesClientWrapper.labelPodAsCancelled(runningPod.get());
            return;
        }
        // No taskmaster runs yet (its pod is pending, or the job has none yet): a pod created
        // later wouldn't get the label, so Kubernetes ends the job instead (issue #126). The job
        // first: it decides; the pending pods' labels are a courtesy, as Kubernetes removes them
        this.kubernetesClientWrapper.cancelJobBeforeStart(taskId);
        taskMasterPods.getItems().stream()
                .filter(pod ->
                        pod.getStatus() != null
                                && PodPhase.PENDING.getCode().equals(pod.getStatus().getPhase()))
                .forEach(pod ->
                        this.labelPodAsCancelledIfPossible(
                                pod.getMetadata().getName(), "its job ends anyway"));
    }

    /**
     * Reads the task to cancel, as the decision service decides: asked for missing tasks too, so
     * that the answer doesn't tell whether a task exists
     *
     * @throws TaskNotFoundException if the task is missing, or the decision service denies and the
     *     caller may not get the task either (404)
     * @throws AccessDeniedException if the decision service denies, but the caller may get the task
     *     (403)
     */
    private Task readTaskToCancel(String taskId, User user) {
        Optional<Task> task = this.readOwnedTask(taskId);
        TaskAccess.CancelDecision decision =
                this.access.mayCancel(user, taskId, task.map(this::facts).orElse(null));
        if (decision == TaskAccess.CancelDecision.FORBIDDEN) {
            throw new AccessDeniedException("Not allowed to cancel task " + taskId);
        }
        if (decision == TaskAccess.CancelDecision.NOT_FOUND || task.isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
        // read again after the decision, which can take a while: the task's current state decides
        // whether it is cancelled, and a task that was deleted, or replaced by another one with the
        // same ID (another job UID), isn't the task the decision service allowed (404)
        String allowedUid = task.get().getTaskmaster().getJob().getMetadata().getUid();
        return this.readOwnedTask(taskId)
                .filter(current ->
                        Objects.equals(
                                allowedUid,
                                current.getTaskmaster().getJob().getMetadata().getUid()))
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private static boolean isStartedAndNotLabelledCancelled(V1Pod pod) {
        boolean started =
                pod.getStatus() != null
                        && (PodPhase.PENDING.getCode().equals(pod.getStatus().getPhase())
                                || PodPhase.RUNNING.getCode().equals(pod.getStatus().getPhase()));
        return started && !TaskStateResolver.isLabelledCancelled(pod.getMetadata());
    }

    /**
     * Labels a taskmaster pod of a canceling task as cancelled; a pod that is gone already (404)
     * has nothing left to stop. Other errors fail the request: the taskmaster would go on.
     */
    private void labelPodAsCancelledUnlessGone(String podName) {
        try {
            this.kubernetesClientWrapper.labelPodAsCancelled(podName);
        } catch (KubernetesException e) {
            if (e.getApiException().getCode() != HttpStatus.NOT_FOUND.value()) {
                throw e;
            }
            logger.info("Pod {} of a cancelled task is gone, not labelled", podName);
        }
    }

    /**
     * Labels a pod as cancelled where the task's job decides already; a pod that is gone already
     * (or can't be patched) is skipped.
     *
     * @param podName the pod's name
     * @param why why the task is cancelled anyway, for the log
     */
    private void labelPodAsCancelledIfPossible(String podName, String why) {
        try {
            this.kubernetesClientWrapper.labelPodAsCancelled(podName);
        } catch (KubernetesException e) {
            logger.info(
                    "Pod {} of a cancelled task not labelled (HTTP {}); {}",
                    podName,
                    e.getApiException().getCode(),
                    why);
        }
    }
}
