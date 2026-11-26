package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_VALUE_CANC;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.models.V1Secret;
import java.io.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.json.JsonContentAssert;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;
import uk.ac.ebi.tsc.tesk.TestUtils;
import uk.ac.ebi.tsc.tesk.config.GsonConfig;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.constant.Constants;
import uk.ac.ebi.tsc.tesk.k8s.constant.K8sConstants;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Job;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.TaskBuilder;
import uk.ac.ebi.tsc.tesk.tes.model.TesExecutor;
import uk.ac.ebi.tsc.tesk.tes.model.TesExecutorLog;
import uk.ac.ebi.tsc.tesk.tes.model.TesResources;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;
import uk.ac.ebi.tsc.tesk.trs.TrsToolClient;

/**
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@JsonTest
@TestPropertySource(locations = {"classpath:application.properties"})
@EnableConfigurationProperties(TaskmasterEnvProperties.class)
public class TesKubernetesConverterMinimalTest {
    @MockitoBean private CoreV1Api coreApi;

    @MockitoBean private JobNameGenerator jobNameGenerator;

    private TrsToolClient trsToolClient;

    @TestConfiguration
    @Import({KubernetesObjectsSupplier.class, GsonConfig.class})
    static class Configuration {}

    @Autowired
    @Qualifier("executor")
    Supplier<V1Job> executorTemplateSupplier;

    @Autowired
    @Qualifier("taskmaster")
    Supplier<V1Job> taskmasterTemplateSupplier;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private Gson gson;

    private TesKubernetesConverter converter;

    @BeforeEach
    public void setUpConverter() {
        trsToolClient = mock(TrsToolClient.class);
        when(trsToolClient.getDockerImageForToolVersionURI(anyString())).then(returnsFirstArg());
        this.converter =
                new TesKubernetesConverter(
                        executorTemplateSupplier,
                        taskmasterTemplateSupplier,
                        objectMapper,
                        gson,
                        trsToolClient);
    }

    @Test
    public void fromTesTaskToK8sJob_minimal() throws IOException {

        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-98605447");

        Resource inputTaskFile = new ClassPathResource("fromTesToK8s_minimal/task.json");
        TesTask inputTask;
        try (InputStream inputStream = inputTaskFile.getInputStream();
                Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            inputTask = this.objectMapper.readValue(reader, TesTask.class);
        }
        V1Job outputJob =
                this.converter.fromTesTaskToK8sJob(inputTask, User.builder("test-user-id").build());
        assertNull(outputJob.getMetadata().getAnnotations().get("tes-task-name"));
        // testing annotation with entire serialized task content..
        assertThat(outputJob.getMetadata().getAnnotations().get("json-input")).isNotEmpty();
        // ..comparing jsons
        JsonContentAssert annotationWithEntireTask =
                new JsonContentAssert(
                        this.getClass(),
                        outputJob.getMetadata().getAnnotations().get("json-input"));
        annotationWithEntireTask.isEqualToJson(inputTaskFile);

        // ..and one example path
        annotationWithEntireTask
                .extractingJsonPathArrayValue("@.executors[0].command")
                .startsWith("echo");

        assertThat(outputJob.getMetadata().getLabels().get("job-type"), is("taskmaster"));
        assertThat(
                outputJob.getMetadata().getLabels().get("creator-user-hash"),
                is("c95b3f800751698e8f935156112885cc"));
        assertThat(
                outputJob.getMetadata().getAnnotations().get("creator-user-id"),
                is("test-user-id"));
        assertFalse(outputJob.getMetadata().getLabels().containsKey("creator-group-name"));

        Resource outputJobFile = new ClassPathResource("fromTesToK8s_minimal/job.json");
        V1Job expectedJob;
        try (InputStream inputStream = outputJobFile.getInputStream();
                Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            expectedJob = this.gson.fromJson(reader, V1Job.class);
        }
        expectedJob.getMetadata().setAnnotations(null);
        outputJob.getMetadata().setAnnotations(null);
        expectedJob.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                .filter(env -> env.getName().equals(Constants.TASKMASTER_INPUT))
                .forEach(env -> env.setValue(""));
        outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                .filter(env -> env.getName().equals(Constants.TASKMASTER_INPUT))
                .forEach(env -> env.setValue(""));
        // comparing fields of resulting Job object and pattern Job objects other those with JSON
        // values, which were cleared in previous lines (JSON strings do not have to be exactly
        // equal to pattern).
        assertEquals(expectedJob, outputJob);
    }

    @Test
    public void fromTesTaskToK8sConfigMap() throws IOException {

        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-98605447");
        Resource inputTaskFile = new ClassPathResource("fromTesToK8s_minimal/task.json");
        TesTask inputTask;
        try (InputStream inputStream = inputTaskFile.getInputStream();
                Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            inputTask = this.objectMapper.readValue(reader, TesTask.class);
        }
        V1Job outputJob =
                this.converter.fromTesTaskToK8sJob(inputTask, User.builder("test-user-id").build());

        V1ConfigMap outputConfigMap =
                this.converter.fromTesTaskToK8sConfigMap(
                        inputTask, User.builder("test-user-id").build(), outputJob);
        Map<String, byte[]> binaryDataMap = outputConfigMap.getBinaryData();
        GZIPInputStream gis =
                new GZIPInputStream(new ByteArrayInputStream(binaryDataMap.get("JSON_INPUT.gz")));
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(gis, "UTF-8"));
        String line;
        String jsonString = "";
        while ((line = bufferedReader.readLine()) != null) {
            jsonString = jsonString + line;
        }

        JsonContentAssert taskMasterInputJson = new JsonContentAssert(this.getClass(), jsonString);
        taskMasterInputJson.hasJsonPathValue("outputs");
        taskMasterInputJson.extractingJsonPathNumberValue("outputs.size()").isEqualTo(0);
        taskMasterInputJson.extractingJsonPathNumberValue("inputs.size()").isEqualTo(0);
        taskMasterInputJson.extractingJsonPathNumberValue("volumes.size()").isEqualTo(0);
        taskMasterInputJson
                .extractingJsonPathMapValue("executors[0].metadata.annotations")
                .containsOnlyKeys("creator-user-id");
        taskMasterInputJson
                .extractingJsonPathArrayValue("executors[*].metadata.labels['job-type']")
                .containsOnly("executor")
                .hasSize(1);
        taskMasterInputJson
                .extractingJsonPathArrayValue("executors[*].metadata.labels['taskmaster-name']")
                .containsOnly("task-98605447")
                .hasSize(1);

        taskMasterInputJson
                .extractingJsonPathArrayValue("executors[*].spec.template.spec.restartPolicy")
                .containsOnly("Never")
                .hasSize(1);
        taskMasterInputJson
                .extractingJsonPathMapValue(
                        "executors[0].spec.template.spec.containers[0].resources")
                .isEqualTo(Map.of("requests", Map.of("cpu", "1"), "limits", Map.of("cpu", "1")));

        taskMasterInputJson
                .extractingJsonPathArrayValue(
                        "executors[0].spec.template.spec.containers[0].command")
                .containsExactly("echo", "hello world");
        taskMasterInputJson
                .extractingJsonPathStringValue(
                        "executors[0].spec.template.spec.containers[0].image")
                .isEqualTo("ubuntu");

        taskMasterInputJson
                .extractingJsonPathMapValue("executors[0].spec.template.spec.containers[0]")
                .containsOnlyKeys("name", "image", "command", "resources");

        taskMasterInputJson.extractingJsonPathNumberValue("resources.disk_gb").isEqualTo(0.1);

        taskMasterInputJson.isEqualToJson(
                new ClassPathResource("fromTesToK8s_minimal/taskmaster_param.json"),
                JSONCompareMode.NON_EXTENSIBLE);
    }

    /**
     * An executor with memory but no CPU gets the default CPU (1, from {@code
     * application.properties}) and its memory as both the requests and the limits: the taskmaster
     * fills in limits from requests only if there are no limits at all, so tesk-api sets the memory
     * limit itself (a pod without a memory limit could use all memory of its node, and one without
     * a CPU limit is refused under a {@code limits.cpu} ResourceQuota)
     */
    @Test
    public void ramOnlyExecutorGetsDefaultCpuAndMemoryLimit() throws IOException {
        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-98605447");
        TesTask task =
                new TesTask()
                        .addExecutorsItem(new TesExecutor().image("ubuntu").addCommandItem("true"))
                        .resources(new TesResources().ramGb(2.0));
        User user = User.builder("test-user-id").build();
        V1Job taskmaster = this.converter.fromTesTaskToK8sJob(task, user);
        V1ConfigMap configMap = this.converter.fromTesTaskToK8sConfigMap(task, user, taskmaster);
        try (InputStreamReader reader =
                new InputStreamReader(
                        new GZIPInputStream(
                                new ByteArrayInputStream(
                                        configMap.getBinaryData().get("JSON_INPUT.gz"))),
                        StandardCharsets.UTF_8)) {
            JsonObject resources =
                    this.gson
                            .fromJson(reader, JsonObject.class)
                            .getAsJsonArray("executors")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonObject("spec")
                            .getAsJsonObject("template")
                            .getAsJsonObject("spec")
                            .getAsJsonArray("containers")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonObject("resources");
            JsonObject expected = new JsonObject();
            expected.addProperty("cpu", "1");
            expected.addProperty("memory", "2147483648");
            assertThat(resources.getAsJsonObject("requests")).isEqualTo(expected);
            assertThat(resources.getAsJsonObject("limits")).isEqualTo(expected);
        }
    }

    @Test
    public void privateExecutorImageUsesOwnedSecretWithoutExposingCredentials() throws IOException {
        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-98605447");
        TesExecutor publicExecutor = new TesExecutor().image("ubuntu").addCommandItem("true");
        TesExecutor privateExecutor =
                new TesExecutor()
                        .image("registry.example.org/project/image:1")
                        .addCommandItem("true")
                        .dockerRegistry("registry.example.org")
                        .dockerLogin("robot")
                        .dockerPass("secret-password");
        TesTask task =
                new TesTask().addExecutorsItem(publicExecutor).addExecutorsItem(privateExecutor);
        User user = User.builder("test-user-id").build();

        V1Job taskmaster = this.converter.fromTesTaskToK8sJob(task, user);
        String annotation = taskmaster.getMetadata().getAnnotations().get("json-input");
        String[] credentialRepresentations = {
            "robot",
            "secret-password",
            "docker_login",
            "docker_pass",
            Base64.getEncoder().encodeToString("robot".getBytes(StandardCharsets.UTF_8)),
            Base64.getEncoder().encodeToString("secret-password".getBytes(StandardCharsets.UTF_8)),
            Base64.getEncoder()
                    .encodeToString("robot:secret-password".getBytes(StandardCharsets.UTF_8))
        };
        org.assertj.core.api.Assertions.assertThat(annotation)
                .contains("registry.example.org")
                .doesNotContain(credentialRepresentations);
        org.assertj.core.api.Assertions.assertThat(privateExecutor.toString())
                .doesNotContain(credentialRepresentations);

        V1ConfigMap configMap = this.converter.fromTesTaskToK8sConfigMap(task, user, taskmaster);
        try (InputStreamReader reader =
                new InputStreamReader(
                        new GZIPInputStream(
                                new ByteArrayInputStream(
                                        configMap.getBinaryData().get("JSON_INPUT.gz"))),
                        StandardCharsets.UTF_8)) {
            JsonObject input = this.gson.fromJson(reader, JsonObject.class);
            String serialized = input.toString();
            org.assertj.core.api.Assertions.assertThat(serialized)
                    .doesNotContain(credentialRepresentations);
            org.assertj.core.api.Assertions.assertThat(
                            input.getAsJsonArray("executors")
                                    .get(0)
                                    .getAsJsonObject()
                                    .getAsJsonObject("spec")
                                    .getAsJsonObject("template")
                                    .getAsJsonObject("spec")
                                    .has("imagePullSecrets"))
                    .isFalse();
            org.assertj.core.api.Assertions.assertThat(
                            input.getAsJsonArray("executors")
                                    .get(1)
                                    .getAsJsonObject()
                                    .getAsJsonObject("spec")
                                    .getAsJsonObject("template")
                                    .getAsJsonObject("spec")
                                    .getAsJsonArray("imagePullSecrets")
                                    .get(0)
                                    .getAsJsonObject()
                                    .get("name")
                                    .getAsString())
                    .isEqualTo("task-98605447-ex-01");
        }

        V1Secret secret = this.converter.fromTesTaskToK8sImagePullSecrets(task, taskmaster).get(0);
        assertEquals("task-98605447-ex-01", secret.getMetadata().getName());
        assertEquals("kubernetes.io/dockerconfigjson", secret.getType());
        assertEquals(
                Base64.getEncoder()
                        .encodeToString("robot:secret-password".getBytes(StandardCharsets.UTF_8)),
                this.objectMapper
                        .readTree(secret.getData().get(".dockerconfigjson"))
                        .path("auths")
                        .path("registry.example.org")
                        .path("auth")
                        .asText());
    }

    @Test
    public void privateExecutorImageKeepsTemplatePullSecrets() {
        Supplier<V1Job> templateWithPullSecret =
                () -> {
                    V1Job job = executorTemplateSupplier.get();
                    job.getSpec()
                            .getTemplate()
                            .getSpec()
                            .addImagePullSecretsItem(
                                    new V1LocalObjectReference().name("shared-pull-secret"));
                    return job;
                };
        TesKubernetesConverter converterWithPullSecret =
                new TesKubernetesConverter(
                        templateWithPullSecret,
                        taskmasterTemplateSupplier,
                        objectMapper,
                        gson,
                        trsToolClient);
        TesExecutor executor =
                new TesExecutor()
                        .image("registry.example.org/project/image:1")
                        .addCommandItem("true")
                        .dockerRegistry("registry.example.org")
                        .dockerLogin("robot")
                        .dockerPass("secret-password");

        V1Job job =
                converterWithPullSecret.fromTesExecutorToK8sJob(
                        "task-98605447",
                        "task name",
                        executor,
                        0,
                        null,
                        User.builder("test-user-id").build());

        org.assertj.core.api.Assertions.assertThat(
                        job.getSpec().getTemplate().getSpec().getImagePullSecrets())
                .extracting(V1LocalObjectReference::getName)
                .containsExactly("shared-pull-secret", "task-98605447-ex-00");
    }

    private TaskBuilder prepareBaseTaskBuider(boolean withExecutors, boolean withPods)
            throws IOException {
        TaskBuilder taskBuilder = TaskBuilder.newSingleTask();
        taskBuilder.addJob(
                this.gson.fromJson(
                        TestUtils.getFileContentFromResources(
                                "fromK8sToTes_minimal/taskmaster.json"),
                        V1Job.class));
        if (withExecutors) {
            taskBuilder.addJob(
                    this.gson.fromJson(
                            TestUtils.getFileContentFromResources(
                                    "fromK8sToTes_minimal/executor_00.json"),
                            V1Job.class));
            taskBuilder.addJob(
                    this.gson.fromJson(
                            TestUtils.getFileContentFromResources(
                                    "fromK8sToTes_minimal/executor_01.json"),
                            V1Job.class));
        }
        if (withPods) {
            taskBuilder.addPodList(
                    this.gson
                            .fromJson(
                                    TestUtils.getFileContentFromResources(
                                            "fromK8sToTes_minimal/taskmaster_pods.json"),
                                    V1PodList.class)
                            .getItems());
            taskBuilder.addPodList(
                    this.gson
                            .fromJson(
                                    TestUtils.getFileContentFromResources(
                                            "fromK8sToTes_minimal/executor_pods.json"),
                                    V1PodList.class)
                            .getItems());
        }
        return taskBuilder;
    }

    private TesTask prepareBaseExpectedTask() throws IOException {
        return this.objectMapper.readValue(
                TestUtils.getFileContentFromResources("fromK8sToTes_minimal/task.json"),
                TesTask.class);
    }

    @Test
    public void fromK8sToTask_complete() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(true, false);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(1);
        // complete only when every executor completed
        taskBuilder
                .getTask()
                .getExecutors()
                .forEach(executor -> executor.getJob().getStatus().succeeded(1));
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.COMPLETE);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    /** Tasks with the hash label: the full ID from the annotation (issue #119) */
    @Test
    public void fromK8sToTask_creatorFromAnnotation() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, false);
        V1ObjectMeta metadata = taskBuilder.getTask().getTaskmaster().getJob().getMetadata();
        metadata.getLabels().remove(Constants.ANN_USERID_KEY);
        CreatorLabels.put(metadata, "user@example.org");
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), false);
        assertThat(
                outputTask.getLogs().get(0).getMetadata().get("USER_ID"), is("user@example.org"));
        assertNull(outputTask.getLogs().get(0).getMetadata().get("GROUP_NAME"));
    }

    /** An annotation that doesn't match the hash label gives no creator (issue #119) */
    @Test
    public void fromK8sToTask_creatorAnnotationNotMatchingHash() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, false);
        V1ObjectMeta metadata = taskBuilder.getTask().getTaskmaster().getJob().getMetadata();
        CreatorLabels.put(metadata, "user@example.org");
        metadata.putAnnotationsItem(Constants.ANN_USERID_KEY, "user_example.org");
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), false);
        assertNull(outputTask.getLogs().get(0).getMetadata());
    }

    /**
     * Tasks with only the old label (and the old group label) have no creator and no group: nobody
     * owns them (issue #225)
     */
    @Test
    public void fromK8sToTask_noCreatorFromLegacyLabels() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, false);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), false);
        assertNull(outputTask.getLogs().get(0).getMetadata());
        assertNull(this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask()).getLogs());
    }

    @Test
    public void fromK8sToTask_cancelled() throws IOException {
        // only taskmaster job matters
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, false);
        taskBuilder
                .getTask()
                .getTaskmaster()
                .getJob()
                .getMetadata()
                .getLabels()
                .putIfAbsent(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.CANCELED);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    @Test
    public void fromK8sToTask_running() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(true, false);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().active(1);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.RUNNING);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    @Test
    public void fromK8sToTask_queued_taskmaster() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, true);
        taskBuilder
                .getTask()
                .getTaskmaster()
                .getFirstPod()
                .getStatus()
                .setPhase(K8sConstants.PodPhase.PENDING.getCode());
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.QUEUED);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    @Test
    public void fromK8sToTask_queued_executor() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(true, true);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().active(1);
        taskBuilder
                .getTask()
                .getLastExecutor()
                .get()
                .getFirstPod()
                .getStatus()
                .setPhase(K8sConstants.PodPhase.PENDING.getCode());
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.QUEUED);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    /** A taskmaster's job without counts or pods, just created, is queued (issue #126) */
    @Test
    public void fromK8sToTask_queued_new_job() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, false);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.QUEUED);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    /** Without a creation time of the taskmaster's job (Kubernetes sets one), the task has none */
    @Test
    public void fromK8sToTask_noCreationTime() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, false);
        taskBuilder.getTask().getTaskmaster().getJob().getMetadata().setCreationTimestamp(null);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), false);
        org.assertj.core.api.Assertions.assertThat(outputTask.getCreationTime()).isNull();
    }

    /** A taskmaster's job with failed pods counted, none active and no condition: an error */
    @Test
    public void fromK8sToTask_system_error_failed_pods() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, false);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().failed(1);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.SYSTEM_ERROR);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    @Test
    public void fromK8sToTask_system_error_no_executors() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, false);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(1);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.SYSTEM_ERROR);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    @Test
    public void fromK8sToTask_system_error_mismatch() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(true, false);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(1);
        taskBuilder.getTask().getLastExecutor().get().getJob().getStatus().active(1);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.SYSTEM_ERROR);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    @Test
    public void fromK8sToTask_system_error_filer() throws IOException {
        TaskBuilder taskBuilder =
                this.prepareBaseTaskBuider(true, false)
                        .addJob(
                                new V1Job()
                                        .metadata(new V1ObjectMeta().putLabelsItem("aa", "vv"))
                                        .status(new V1JobStatus().failed(2)));
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(1);
        taskBuilder.getTask().getLastExecutor().get().getJob().getStatus().succeeded(1);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.SYSTEM_ERROR);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    @Test
    public void fromK8sToTask_executor_error() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(true, false);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(1);
        taskBuilder.getTask().getLastExecutor().get().getJob().getStatus().failed(1);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.EXECUTOR_ERROR);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    @Test
    public void fromK8sToTask_initializing() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false, false);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().active(1);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.INITIALIZING);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskMinimal(taskBuilder.getTask());
        assertThat(outputTask, CoreMatchers.is(expectedTask));
    }

    /**
     * New tasks get no group label or annotation, also with a {@code GROUP_NAME} tag, which is just
     * a tag now (issue #225); their labels are valid label values, also for long creator IDs
     */
    @Test
    public void noGroupLabels() {
        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-1");
        StringBuilder longId = new StringBuilder("user|");
        for (int i = 0; i < 70; i++) {
            longId.append("x");
        }
        User user = User.builder(longId.toString()).build();
        for (TesTask task : new TesTask[] {task(), task().putTagsItem("GROUP_NAME", "ABC")}) {
            V1Job job = this.converter.fromTesTaskToK8sJob(task, user);
            V1ConfigMap configMap = this.converter.fromTesTaskToK8sConfigMap(task, user, job);
            for (V1ObjectMeta metadata :
                    new V1ObjectMeta[] {job.getMetadata(), configMap.getMetadata()}) {
                for (String value : metadata.getLabels().values()) {
                    assertTrue(LABEL_VALUE.matcher(value).matches(), value);
                }
                assertFalse(metadata.getLabels().containsKey("creator-group-name"));
                assertFalse(metadata.getAnnotations().containsKey("creator-group-name"));
                assertEquals(longId.toString(), CreatorLabels.creatorId(metadata));
            }
        }
    }

    /** Valid label values: alphanumeric at both ends, at most 63 characters */
    private static final Pattern LABEL_VALUE =
            Pattern.compile("^(([A-Za-z0-9][-A-Za-z0-9_.]{0,61})?[A-Za-z0-9])?$");

    private static TesTask task() {
        return new TesTask()
                .addExecutorsItem(
                        new TesExecutor()
                                .image("ubuntu")
                                .command(Collections.singletonList("true")));
    }

    /**
     * A task that can't be serialized isn't created: without its JSON, it could be neither run nor
     * read
     */
    @Test
    public void serializationFailure_failsTheConversion() throws IOException {
        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-1");
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any()))
                .thenThrow(
                        DatabindException.from(
                                (tools.jackson.core.JsonParser) null, "not serializable"));
        TesKubernetesConverter failingConverter =
                new TesKubernetesConverter(
                        executorTemplateSupplier,
                        taskmasterTemplateSupplier,
                        failing,
                        gson,
                        trsToolClient);
        TesTask task =
                new TesTask()
                        .addExecutorsItem(
                                new TesExecutor()
                                        .image("ubuntu")
                                        .command(Collections.singletonList("true")));
        User user = User.builder("user-1").build();

        assertThatThrownBy(() -> failingConverter.fromTesTaskToK8sJob(task, user))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("not serializable");
        V1Job job = this.converter.fromTesTaskToK8sJob(task, user);
        assertThatThrownBy(() -> failingConverter.fromTesTaskToK8sConfigMap(task, user, job))
                .isInstanceOf(JacksonException.class);
    }

    /** An IOException of an action (e.g. compressing the taskmaster's input) fails unchecked */
    @Test
    public void unchecked_wrapsIOException() {
        assertThat(TesKubernetesConverter.unchecked(() -> "done")).isEqualTo("done");
        assertThatThrownBy(() ->
                        TesKubernetesConverter.unchecked(() -> {
                            throw new IOException("disk full");
                        }))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("disk full");
    }

    /** An executor whose pod reports no containers yet has no exit code */
    @Test
    public void executorLog_podWithoutContainerStatuses() {
        Job executor =
                new Job(
                        new V1Job()
                                .metadata(new V1ObjectMeta().name("task-1-ex-00"))
                                .status(new V1JobStatus()));
        executor.addPod(
                new V1Pod()
                        .metadata(new V1ObjectMeta().name("task-1-ex-00-abcde"))
                        .status(new V1PodStatus().containerStatuses(Collections.emptyList())));

        TesExecutorLog log = this.converter.extractExecutorLogFromK8sJobAndPod(executor);

        assertNull(log.getExitCode());
        assertEquals("", log.getStdout());
    }

    /** The FULL view keeps the content of inputs, BASIC leaves it out */
    @Test
    public void inputContent_onlyInFullView() {
        V1Job taskmaster =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("task-1")
                                        .putLabelsItem(
                                                Constants.LABEL_JOBTYPE_KEY,
                                                Constants.LABEL_JOBTYPE_VALUE_TASKM)
                                        .putAnnotationsItem(
                                                Constants.ANN_JSON_INPUT_KEY,
                                                "{\"inputs\":[{\"path\":\"/in\",\"content\":\"hi\"}]}"));
        uk.ac.ebi.tsc.tesk.k8s.convert.data.Task task =
                new uk.ac.ebi.tsc.tesk.k8s.convert.data.Task(new Job(taskmaster));

        assertEquals(
                "hi",
                this.converter
                        .fromK8sJobsToTesTaskBasic(task, false)
                        .getInputs()
                        .get(0)
                        .getContent());
        assertNull(
                this.converter
                        .fromK8sJobsToTesTaskBasic(task, true)
                        .getInputs()
                        .get(0)
                        .getContent());
    }

    /** A task without inputs has none to clear in the BASIC view */
    @Test
    public void basicView_taskWithoutInputs() {
        V1Job taskmaster =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name("task-1")
                                        .putLabelsItem(
                                                Constants.LABEL_JOBTYPE_KEY,
                                                Constants.LABEL_JOBTYPE_VALUE_TASKM)
                                        .putAnnotationsItem(
                                                Constants.ANN_JSON_INPUT_KEY,
                                                "{\"name\":\"no inputs\"}"));

        TesTask task =
                this.converter.fromK8sJobsToTesTaskBasic(
                        new uk.ac.ebi.tsc.tesk.k8s.convert.data.Task(new Job(taskmaster)), true);

        assertEquals("task-1", task.getId());
        assertEquals("no inputs", task.getName());
        assertNull(task.getInputs());
    }
}
