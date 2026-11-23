package uk.ac.ebi.tsc.tesk.k8s.convert;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.*;
import static uk.ac.ebi.tsc.tesk.k8s.constant.K8sConstants.*;

import com.google.gson.Gson;
import io.kubernetes.client.custom.QuantityFormatter;
import io.kubernetes.client.openapi.models.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.config.KubernetesJson;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Job;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Task;
import uk.ac.ebi.tsc.tesk.tes.model.*;
import uk.ac.ebi.tsc.tesk.trs.TrsToolClient;

/**
 * Conversion of TES objects to and from Kubernetes Objects
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@Component
public class TesKubernetesConverter {

    private static final Logger logger = LoggerFactory.getLogger(TesKubernetesConverter.class);

    private final Supplier<V1Job> executorTemplateSupplier;

    private final Supplier<V1Job> taskmasterTemplateSupplier;

    private final ObjectMapper objectMapper;

    private final Gson gson;

    private final TrsToolClient trsClient;

    /** Times in UTC, with milliseconds (e.g. 2018-05-31T09:21:57.000Z) */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);

    public TesKubernetesConverter(
            @Qualifier("executor") Supplier<V1Job> executorTemplateSupplier,
            @Qualifier("taskmaster") Supplier<V1Job> taskmasterTemplateSupplier,
            ObjectMapper objectMapper,
            Gson gson,
            TrsToolClient trsClient) {
        this.executorTemplateSupplier = executorTemplateSupplier;
        this.taskmasterTemplateSupplier = taskmasterTemplateSupplier;
        this.objectMapper = objectMapper;
        this.gson = gson;
        this.trsClient = trsClient;
    }

    /**
     * Converts TES task to new K8s Job object with random generated name
     *
     * @param task - TES Task input object
     * @return K8s Job Object
     */
    @SuppressWarnings("unchecked")
    public V1Job fromTesTaskToK8sJob(TesTask task, User user) {
        // get new Job template with random generated name;
        V1Job taskMasterJob = this.taskmasterTemplateSupplier.get();
        // put input task name as annotation
        taskMasterJob.getMetadata().putAnnotationsItem(ANN_TESTASK_NAME_KEY, task.getName());
        // creating user and owning group
        CreatorLabels.put(taskMasterJob.getMetadata(), user.getUsername());
        GroupLabels.put(taskMasterJob.getMetadata(), groupOf(task, user));
        // in order to retrieve task details, when querying for task details, whole tesTask
        // object is placed as taskMaster's annotation
        // Jackson for TES objects - because, we rely on auto-generated annotations for Json
        // mapping
        taskMasterJob
                .getMetadata()
                .putAnnotationsItem(ANN_JSON_INPUT_KEY, this.objectMapper.writeValueAsString(task));

        V1Volume volume = new V1Volume();
        volume.setName("jsoninput");
        V1ConfigMapVolumeSource src = new V1ConfigMapVolumeSource();
        src.setName(taskMasterJob.getMetadata().getName());
        volume.setConfigMap(src);
        taskMasterJob.getSpec().getTemplate().getSpec().addVolumesItem(volume);

        return taskMasterJob;
    }

    /**
     * Converts TES task to new K8s ConfigMap object with random generated name
     *
     * @param task - TES Task input object
     * @return K8s Job Object
     */
    @SuppressWarnings("unchecked")
    public V1ConfigMap fromTesTaskToK8sConfigMap(TesTask task, User user, V1Job job) {
        // get new Job template with random generated name;
        V1ConfigMap taskMasterConfigMap = new V1ConfigMap();
        taskMasterConfigMap.setMetadata(new V1ObjectMeta());
        taskMasterConfigMap.getMetadata().setName(job.getMetadata().getName());
        // put input task name as annotation
        taskMasterConfigMap.getMetadata().putAnnotationsItem(ANN_TESTASK_NAME_KEY, task.getName());
        // creating user and owning group
        CreatorLabels.put(taskMasterConfigMap.getMetadata(), user.getUsername());
        GroupLabels.put(taskMasterConfigMap.getMetadata(), groupOf(task, user));
        List<V1Job> executorsAsJobs =
                IntStream.range(0, task.getExecutors().size())
                        .mapToObj(i ->
                                this.fromTesExecutorToK8sJob(
                                        taskMasterConfigMap.getMetadata().getName(),
                                        task.getName(),
                                        task.getExecutors().get(i),
                                        i,
                                        task.getResources(),
                                        user))
                        .collect(Collectors.toList());
        List<TesInput> inputs = task.getInputs() == null ? new ArrayList<>() : task.getInputs();
        List<TesOutput> outputs = task.getOutputs() == null ? new ArrayList<>() : task.getOutputs();
        List<String> volumes = task.getVolumes() == null ? new ArrayList<>() : task.getVolumes();
        TesTask taskmasterTask =
                new TesTask()
                        .inputs(inputs)
                        .outputs(outputs)
                        .volumes(volumes)
                        .resources(
                                new TesResources()
                                        .diskGb(
                                                Optional.ofNullable(task.getResources())
                                                        .map(TesResources::getDiskGb)
                                                        .orElse(RESOURCE_DISK_DEFAULT)));
        String jobAsJson = this.objectMapper.writeValueAsString(taskmasterTask);
        Map<String, Object> taskMasterInput = new HashMap<>(gson.fromJson(jobAsJson, Map.class));
        // without the empty lists and maps of the client's models, as before (see
        // KubernetesJson)
        taskMasterInput.put(
                TASKMASTER_INPUT_EXEC_KEY,
                executorsAsJobs.stream()
                        .map(KubernetesJson.GSON_WITHOUT_EMPTY_COLLECTIONS::toJsonTree)
                        .collect(Collectors.toList()));
        String taskMasterInputAsJSON = this.gson.toJson(taskMasterInput);
        taskMasterConfigMap.putBinaryDataItem(
                TASKMASTER_INPUT + ".gz", unchecked(() -> gzip(taskMasterInputAsJSON)));
        return taskMasterConfigMap;
    }

    /** An action that may fail with an IOException */
    @FunctionalInterface
    interface IoSupplier<T> {
        T get() throws IOException;
    }

    /**
     * The result of the action, failing with an unchecked exception instead of an IOException (e.g.
     * if the taskmaster's input can't be compressed): a task without it couldn't run
     */
    static <T> T unchecked(IoSupplier<T> action) {
        try {
            return action.get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text, encoded in UTF-8 and gzipped */
    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /** Creates one Docker registry Secret per executor that has registry credentials. */
    public List<V1Secret> fromTesTaskToK8sImagePullSecrets(TesTask task, V1Job taskMasterJob) {
        List<V1Secret> secrets = new ArrayList<>();
        String taskId = taskMasterJob.getMetadata().getName();
        for (int i = 0; i < task.getExecutors().size(); i++) {
            TesExecutor executor = task.getExecutors().get(i);
            if (executor.getDockerRegistry() == null) {
                continue;
            }
            String auth =
                    Base64.getEncoder()
                            .encodeToString(
                                    (executor.getDockerLogin() + ":" + executor.getDockerPass())
                                            .getBytes(StandardCharsets.UTF_8));
            Map<String, Object> dockerConfig =
                    Collections.singletonMap(
                            "auths",
                            Collections.singletonMap(
                                    executor.getDockerRegistry(),
                                    Collections.singletonMap("auth", auth)));
            byte[] dockerConfigJson =
                    this.gson.toJson(dockerConfig).getBytes(StandardCharsets.UTF_8);
            V1Secret secret =
                    new V1Secret()
                            .apiVersion("v1")
                            .kind("Secret")
                            .metadata(new V1ObjectMeta().name(new Task(taskId).getExecutorName(i)))
                            .type("kubernetes.io/dockerconfigjson");
            secret.setData(Collections.singletonMap(".dockerconfigjson", dockerConfigJson));
            secrets.add(secret);
        }
        return secrets;
    }

    /**
     * Converts TES executor to new K8s Job object, that is passed to taskMaster as part of input
     * parameters Name of executor job relies on taskMaster job's name (taskmaster's jobs name +
     * constant suffix)
     *
     * @param generatedTaskId - random generated job's name == task id
     * @param tesTaskName - input task name
     * @param executor - TES executor input object
     * @param executorIndex - ordinal number of executor
     * @param resources - input task resources
     * @param user - creator of the task
     * @return - executor K8s job object. To be placed in taskMaster input JSON map in the list of
     *     executors
     */
    public V1Job fromTesExecutorToK8sJob(
            String generatedTaskId,
            String tesTaskName,
            TesExecutor executor,
            int executorIndex,
            TesResources resources,
            User user) {
        // get new template executor Job object
        V1Job job = executorTemplateSupplier.get();
        // set executors name based on taskmaster's job name
        new Job(job).changeJobName(new Task(generatedTaskId).getExecutorName(executorIndex));
        // put arbitrary labels and annotations:
        // the important one --> taskId - to search for executors of a given task
        job.getMetadata().putLabelsItem(LABEL_TESTASK_ID_KEY, generatedTaskId);
        job.getMetadata()
                .putLabelsItem(LABEL_EXECNO_KEY, Integer.valueOf(executorIndex).toString());
        job.getMetadata().putAnnotationsItem(ANN_TESTASK_NAME_KEY, tesTaskName);
        // The taskmaster goes on after such an executor fails (issue #159)
        if (Boolean.TRUE.equals(executor.getIgnoreError())) {
            job.getMetadata().putAnnotationsItem(ANN_IGNORE_ERROR_KEY, ANN_IGNORE_ERROR_VALUE);
        }
        CreatorLabels.put(job.getMetadata(), user.getUsername());
        V1Container container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        // Tries to convert potential TRS URI into docker image. If conversion unsuccessful ->
        // leaves the original image value.
        container.image(this.trsClient.getDockerImageForToolVersionURI(executor.getImage()));
        if (executor.getDockerRegistry() != null) {
            job.getSpec()
                    .getTemplate()
                    .getSpec()
                    .addImagePullSecretsItem(
                            new V1LocalObjectReference().name(job.getMetadata().getName()));
        }
        // Should we map executor's command to job container's command (==ENTRYPOINT) or job
        // container's args (==CMD)?
        // It will be command == ENTRYPOINT, because of shell requirement and no custom entrypoints
        // in bio tools.
        new ExecutorCommandWrapper(executor)
                .getCommandsWithStreamRedirects()
                .forEach(container::addCommandItem);
        if (executor.getEnv() != null) {
            executor.getEnv()
                    .forEach((key, value) ->
                            container.addEnvItem(new V1EnvVar().name(key).value(value)));
        }
        container.setWorkingDir(executor.getWorkdir());
        // CPU cores (validated: at most 6 integer digits and 3 decimals) as both the request and
        // the limit, instead of the default of the executors' template, if any
        Optional.ofNullable(resources)
                .map(TesResources::getCpuCores)
                .map(cpuCores -> new QuantityFormatter().parse(cpuCores.toPlainString()))
                .ifPresent(cpu ->
                        container
                                .getResources()
                                .putLimitsItem(RESOURCE_CPU_KEY, cpu)
                                .putRequestsItem(RESOURCE_CPU_KEY, cpu));
        // RAM (limited to 6 decimals) as both the request and the limit: the taskmaster fills in
        // limits from requests only if there are no limits at all (e.g. none with CPU cores)
        Optional.ofNullable(resources)
                .map(TesResources::getRamGb)
                .map(ramGb ->
                        new QuantityFormatter()
                                .parse(
                                        String.format(Locale.ROOT, "%.6f", ramGb)
                                                + RESOURCE_MEM_UNIT))
                .ifPresent(memory ->
                        container
                                .getResources()
                                .putRequestsItem(RESOURCE_MEM_KEY, memory)
                                .putLimitsItem(RESOURCE_MEM_KEY, memory));

        return job;
    }

    /**
     * Retrieves TesCreateTaskResponse from K8s job. At the moment it only wraps job's name/task's
     * id.
     *
     * @param job - K8s taskMaster job
     * @return - TesCreateTaskResponse wrapping task ID
     */
    public TesCreateTaskResponse fromK8sJobToTesCreateTaskResponse(V1Job job) {
        return new TesCreateTaskResponse().id(job.getMetadata().getName());
    }

    /**
     * Derives TES task's status from task's object graph (packed into {@link Task} object): the
     * taskmaster's, executors' and outputs filer's jobs and their pods (see {@link
     * TaskStateResolver}). The number of the task's executors comes from the task stored with the
     * taskmaster's job.
     *
     * @param taskmasterWithExecutors - taskMaster's full object graph
     * @return TES task status
     */
    public TesState extractStateFromK8sJobs(Task taskmasterWithExecutors) {
        // the stored task is read only if needed: once the taskmaster's job completed
        V1Job taskmasterJob = taskmasterWithExecutors.getTaskmaster().getJob();
        return TaskStateResolver.resolve(
                taskmasterWithExecutors,
                () -> this.extractExecutorCount(taskmasterJob),
                () -> this.extractHasOutputs(taskmasterJob));
    }

    /**
     * Reads whether the task stored with the taskmaster's job (annotation {@code json-input}) has
     * outputs.
     *
     * @param taskMasterJob - taskmaster's job
     * @return whether the task has outputs, or null if the task isn't stored or can't be read
     */
    Boolean extractHasOutputs(V1Job taskMasterJob) {
        if (this.readStoredTaskField(taskMasterJob, "executors") == null) {
            return null;
        }
        JsonNode outputs = this.readStoredTaskField(taskMasterJob, "outputs");
        return outputs != null && outputs.isArray() && outputs.size() > 0;
    }

    /**
     * Reads the number of the task's executors from the task stored with the taskmaster's job
     * (annotation {@code json-input}).
     *
     * @param taskMasterJob - taskmaster's job
     * @return the number of executors, or null if the task isn't stored or can't be read
     */
    Integer extractExecutorCount(V1Job taskMasterJob) {
        JsonNode executors = this.readStoredTaskField(taskMasterJob, "executors");
        return executors != null && executors.isArray() ? executors.size() : null;
    }

    /**
     * Reads the tags of the task stored with the taskmaster's job (annotation {@code json-input}),
     * for the tag filter of listTasks.
     *
     * @param taskMasterJob - taskmaster's job
     * @return the tags (a tag whose value is JSON null has the value null), or null if the task has
     *     none, or isn't stored or can't be read
     */
    public Map<String, String> extractTags(V1Job taskMasterJob) {
        JsonNode tags = this.readStoredTaskField(taskMasterJob, "tags");
        if (tags == null || !tags.isObject()) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        tags.properties().forEach(tag -> result.put(tag.getKey(), tagValue(tag.getValue())));
        return result;
    }

    /**
     * A stored tag value as text: null stays null, objects and arrays (only in a stored task
     * changed by hand) are empty, as with Jackson 2, so that they don't fail the listing of tasks
     */
    private static String tagValue(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        return value.isContainer() ? "" : value.asString();
    }

    /**
     * Reads the files that the outputs filer uploaded, which the taskmaster stores as the
     * annotation {@link uk.ac.ebi.tsc.tesk.k8s.constant.Constants#ANN_OUTPUT_FILE_LOGS_KEY} of the
     * outputs filer's job (issue #160).
     *
     * @param task the task's jobs
     * @return the files, each with {@code url}, {@code path} and {@code size_bytes}; empty without
     *     an outputs filer's job or the annotation (e.g. an older taskmaster), or if the annotation
     *     isn't a list of such files (other keys of a file are ignored)
     */
    List<TesOutputFileLog> extractOutputFileLogs(Task task) {
        String json =
                task.getOutputFiler()
                        .map(Job::getJob)
                        .map(V1Job::getMetadata)
                        .map(V1ObjectMeta::getAnnotations)
                        .map(annotations -> annotations.get(ANN_OUTPUT_FILE_LOGS_KEY))
                        .orElse(null);
        List<TesOutputFileLog> logs = new ArrayList<>();
        if (json == null) {
            return logs;
        }
        // the reasons name no values: the URLs may carry credentials, e.g. presigned ones
        String reason = null;
        try {
            JsonNode files = this.objectMapper.readTree(json);
            if (!files.isArray()) {
                reason = "not a list";
            } else {
                for (JsonNode file : files) {
                    if (!isOutputFileLog(file)) {
                        reason = "entry " + logs.size() + " isn't a file";
                        break;
                    }
                    logs.add(
                            new TesOutputFileLog()
                                    .url(file.get("url").asString())
                                    .path(file.get("path").asString())
                                    .sizeBytes(file.get("size_bytes").asString()));
                }
            }
        } catch (JacksonException ex) {
            reason = "not JSON";
        }
        if (reason != null) {
            logger.info(
                    "Reading the output file logs of task {} failed: {}",
                    task.getTaskmaster().getJob().getMetadata().getName(),
                    reason);
            return new ArrayList<>();
        }
        return logs;
    }

    /**
     * Whether a stored file has the text fields url, path and size_bytes, the last a whole number.
     */
    private static boolean isOutputFileLog(JsonNode file) {
        return file.isObject()
                && file.path("url").isString()
                && file.path("path").isString()
                && file.path("size_bytes").isString()
                && file.path("size_bytes").asString().matches("[0-9]+");
    }

    /**
     * Whether the task stored with the taskmaster's job (annotation {@code json-input}) may have
     * filer jobs: taskmasters of all tesk-core releases run filers only for tasks with inputs,
     * outputs or volumes (before tesk-core 0.11.0, the outputs filer for any of them). Used to
     * decide whether the unlabelled filer jobs of older taskmasters are needed (issue #177).
     *
     * @param taskMasterJob - taskmaster's job
     * @return false only if the stored task can be read and has no inputs, outputs and volumes
     */
    public boolean mayHaveFilerJobs(V1Job taskMasterJob) {
        JsonNode task = this.readStoredTask(taskMasterJob);
        if (task == null || task.get("executors") == null) {
            return true;
        }
        return Arrays.asList("inputs", "outputs", "volumes").stream()
                .map(task::get)
                .anyMatch(field -> field != null && field.isArray() && field.size() > 0);
    }

    /**
     * Reads a field of the task stored with the taskmaster's job (annotation {@code json-input}).
     *
     * @return the field, or null if the task isn't stored, can't be read, or has no such field
     */
    private JsonNode readStoredTaskField(V1Job taskMasterJob, String field) {
        return Optional.ofNullable(this.readStoredTask(taskMasterJob))
                .map(tree -> tree.get(field))
                .orElse(null);
    }

    /**
     * Reads the task stored with the taskmaster's job (annotation {@code json-input}).
     *
     * @return the task's JSON tree, or null if the task isn't stored or can't be read
     */
    private JsonNode readStoredTask(V1Job taskMasterJob) {
        String inputJson =
                Optional.ofNullable(taskMasterJob.getMetadata().getAnnotations())
                        .map(ann -> ann.get(ANN_JSON_INPUT_KEY))
                        .orElse(null);
        if (inputJson == null) {
            return null;
        }
        try {
            // readTree gives null or a missing node for empty input, depending on the Jackson
            // version
            return this.objectMapper.readTree(inputJson);
        } catch (JacksonException ex) {
            logger.info(
                    "Reading the task {} from JSON failed; {}",
                    taskMasterJob.getMetadata().getName(),
                    ex.getMessage());
            return null;
        }
    }

    /**
     * Extracts TesExecutorLog from executor job and pod objects !! does not contain stdout (which
     * needs access to pod log)
     *
     * @param executor - executor job and pods object
     * @return - TesExecutorLog object (part of the BASIC/FULL output)
     */
    public TesExecutorLog extractExecutorLogFromK8sJobAndPod(Job executor) {
        TesExecutorLog log = new TesExecutorLog();
        V1Job executorJob = executor.getJob();
        // TODO - better return controller startTime, when possible (now it behaves as if started,
        // even if it is pending)
        log.setStartTime(
                Optional.ofNullable(executorJob.getStatus())
                        .map(V1JobStatus::getStartTime)
                        .map(DATE_FORMATTER::format)
                        .orElse(null));
        log.setEndTime(
                Optional.ofNullable(executorJob.getStatus())
                        .map(V1JobStatus::getCompletionTime)
                        .map(DATE_FORMATTER::format)
                        .orElse(null));
        // workaround for py-tes compatibility. py-tes breaks for ExecutorLogs that can unmarshal to
        // TaskLogs. Non-null stdout prevents that.
        log.setStdout("");
        if (executor.hasPods()) {
            log.setExitCode(
                    Optional.ofNullable(executor.getFirstPod().getStatus())
                            .map(V1PodStatus::getContainerStatuses)
                            .map(list -> list.size() > 0 ? list.get(0) : null)
                            .map(V1ContainerStatus::getState)
                            .map(V1ContainerState::getTerminated)
                            .map(V1ContainerStateTerminated::getExitCode)
                            .orElse(null));
        }
        return log;
    }

    /**
     * The group (team) that a new task belongs to: the one in {@code tags['GROUP_NAME']}, otherwise
     * any group of its creator; null for none
     */
    private static String groupOf(TesTask task, User user) {
        if (task.getTags() != null && task.getTags().containsKey("GROUP_NAME")) {
            return task.getTags().get("GROUP_NAME");
        }
        return user.isMember() ? user.getAnyGroup() : null;
    }

    /** Puts the task's group (team), if it has one, as {@code GROUP_NAME} into the log metadata */
    private static void putGroup(TesTaskLog log, V1ObjectMeta metadata) {
        String group = GroupLabels.groupName(metadata);
        if (group != null) {
            log.putMetadataItem("GROUP_NAME", group);
        }
    }

    /**
     * Puts the creating user's ID into the log's metadata ({@code USER_ID}): the full ID of tasks
     * with the hash label (if the annotation matches it), or the value of the old label of tasks
     * created before, which the log marks as such (see {@link CreatorLabels})
     */
    private static void putCreator(TesTaskLog log, V1ObjectMeta metadata) {
        String creator;
        if (CreatorLabels.isLegacy(metadata)) {
            creator = CreatorLabels.legacyCreator(metadata);
            log.setUserIdFromLegacyLabel(true);
        } else {
            creator = CreatorLabels.creatorId(metadata);
        }
        if (creator != null) {
            log.putMetadataItem("USER_ID", creator);
        }
    }

    /** Extracts minimal view of TesTask from taskMaster's and executors' job and pod objects */
    public TesTask fromK8sJobsToTesTaskMinimal(Task taskmasterWithExecutors, boolean isList) {
        TesTask task = new TesTask();
        V1ObjectMeta metadata = taskmasterWithExecutors.getTaskmaster().getJob().getMetadata();
        task.setId(metadata.getName());
        task.setState(this.extractStateFromK8sJobs(taskmasterWithExecutors));
        // we need that info only for postAuthorization of a getTask; for list auth is handled at
        // the query level
        if (!isList) {
            TesTaskLog log = new TesTaskLog();
            task.addLogsItem(log);
            putCreator(log, metadata);
            putGroup(log, metadata);
        }
        return task;
    }

    /**
     * Extracts basic view of TesTask from taskMaster's and executors' job and pod objects
     *
     * @param nullifyInputContent - true, if input.content has to be removed from the result (in
     *     BASIC view)
     */
    public TesTask fromK8sJobsToTesTaskBasic(
            Task taskmasterWithExecutors, boolean nullifyInputContent) {
        TesTask task = new TesTask();
        V1Job taskMasterJob = taskmasterWithExecutors.getTaskmaster().getJob();
        V1ObjectMeta taskMasterJobMetadata = taskMasterJob.getMetadata();
        String inputJson =
                Optional.ofNullable(taskMasterJobMetadata.getAnnotations())
                        .map(ann -> ann.get(ANN_JSON_INPUT_KEY))
                        .orElse("");
        try {
            task = this.objectMapper.readValue(inputJson, TesTask.class);
            // logs sent with the task aren't TESK's: its own log must be the only one
            task.setLogs(null);
            // tasks created before TESK dropped backend parameters may have them stored, but TES
            // 1.1 says that unsupported keys aren't returned
            IgnoredBackendParameters.removeFrom(task);
            if (nullifyInputContent && task.getInputs() != null) {
                task.getInputs().forEach(input -> input.setContent(null));
            }
        } catch (JacksonException ex) {
            logger.info(
                    "Deserializing task {} from JSON failed; {}",
                    taskMasterJobMetadata.getName(),
                    ex.getMessage());
        }
        task.setId(taskMasterJobMetadata.getName());
        task.setState(this.extractStateFromK8sJobs(taskmasterWithExecutors));
        // Kubernetes sets it for every job it stores
        task.setCreationTime(
                Optional.ofNullable(taskMasterJobMetadata.getCreationTimestamp())
                        .map(DATE_FORMATTER::format)
                        .orElse(null));
        TesTaskLog log = new TesTaskLog();
        task.addLogsItem(log);
        // the files that the outputs filer uploaded, as the taskmaster stored them (issue #160)
        log.setOutputs(this.extractOutputFileLogs(taskmasterWithExecutors));
        putCreator(log, taskMasterJobMetadata);
        putGroup(log, taskMasterJobMetadata);
        log.setStartTime(
                Optional.ofNullable(taskMasterJob.getStatus())
                        .map(V1JobStatus::getStartTime)
                        .map(DATE_FORMATTER::format)
                        .orElse(null));
        log.setEndTime(
                Optional.ofNullable(taskMasterJob.getStatus())
                        .map(V1JobStatus::getCompletionTime)
                        .map(DATE_FORMATTER::format)
                        .orElse(null));
        for (Job executorJob : taskmasterWithExecutors.getExecutors()) {
            TesExecutorLog executorLog = this.extractExecutorLogFromK8sJobAndPod(executorJob);
            task.getLogs().get(0).addLogsItem(executorLog);
        }
        return task;
    }

    public Optional<String> getNameOfFirstRunningPod(V1PodList podList) {
        return podList.getItems().stream()
                .filter(pod ->
                        pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase()))
                .findFirst()
                .map(pod -> pod.getMetadata().getName());
    }
}
