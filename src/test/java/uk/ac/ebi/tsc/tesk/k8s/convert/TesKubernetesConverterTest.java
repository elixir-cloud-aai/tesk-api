package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.junit.Assert.*;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_VALUE_CANC;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import java.io.*;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.json.JsonContentAssert;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;
import uk.ac.ebi.tsc.tesk.TestUtils;
import uk.ac.ebi.tsc.tesk.config.GsonConfig;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.constant.Constants;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.TaskBuilder;
import uk.ac.ebi.tsc.tesk.tes.model.TesExecutor;
import uk.ac.ebi.tsc.tesk.tes.model.TesResources;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;
import uk.ac.ebi.tsc.tesk.trs.TrsToolClient;

/** @author Ania Niewielska <aniewielska@ebi.ac.uk> */
@RunWith(SpringRunner.class)
@JsonTest
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "tesk.api.taskmaster.image-name = task-full-image-name",
            "tesk.api.taskmaster.image-version = task-full-image-version",
            "tesk.api.taskmaster.filer-image-version = task-full-filer-image-version",
            "tesk.api.taskmaster.filer-image-name = task-full-filer-image-name",
            "tesk.api.taskmaster.ftp.secret-name = secretstorage",
            "tesk.api.taskmaster.service-account-name = custom-service-account",
            "tesk.api.taskmaster.debug = true",
            "tesk.api.taskmaster.environment.wes.base.path = /usr/sth/path",
            "tesk.api.taskmaster.environment.TES_BASE_PATH = /tesk/share",
            "tesk.api.taskmaster.executor-secret.name = mysecret",
            "tesk.api.taskmaster.executor-secret.mount-path = /etc/secret",
            "tesk.api.k8s.namespace = test-namespace"
        })
@EnableConfigurationProperties(TaskmasterEnvProperties.class)
public class TesKubernetesConverterTest {
    @MockBean private CoreV1Api coreApi;

    @MockBean private JobNameGenerator jobNameGenerator;

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

    @Autowired private TaskmasterEnvProperties taskmasterEnvProperties;

    private TesKubernetesConverter converter;

    @Rule public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

    @Before
    public void setUpKubernetes() throws ApiException, IOException {
        String configMapName = "testconf";
        String configMapNamespace = "test-namespace";
        String configMapKey = "testkey";
        environmentVariables.set("CONFIGMAP", configMapName);
        environmentVariables.set("CONFIGMAPNAMESPACE", configMapNamespace);
        environmentVariables.set("CONFIGMAPKEY", configMapKey);
        Map<String, String> testConfigMapData = new HashMap<>();
        testConfigMapData.put(
                configMapKey, "fsGroup: 1000\nrunAsUser: 1001\nnonRoot: true\nrunAsGroup: 1002");
        V1ConfigMap testConfigMap = new V1ConfigMap();
        testConfigMap.setData(testConfigMapData);
        when(coreApi.readNamespacedConfigMap(configMapName, configMapNamespace, null, false, false))
                .thenReturn(testConfigMap);
        KubernetesObjectsSupplier objectsSupplier =
                new KubernetesObjectsSupplier(
                        gson, jobNameGenerator, taskmasterEnvProperties, "test-namespace", coreApi);
        this.executorTemplateSupplier = objectsSupplier.executorSupplier();
        this.taskmasterTemplateSupplier = objectsSupplier.taskMasterSupplier();
    }

    @Before
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
    public void hasCorrectSecurityContext() {
        assertNotNull(
                executorTemplateSupplier
                        .get()
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getSecurityContext());
        assertEquals(
                1000L,
                executorTemplateSupplier
                        .get()
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getSecurityContext()
                        .getFsGroup()
                        .longValue());
        assertEquals(
                1001L,
                executorTemplateSupplier
                        .get()
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getSecurityContext()
                        .getRunAsUser()
                        .longValue());
        assertEquals(
                1002L,
                executorTemplateSupplier
                        .get()
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getSecurityContext()
                        .getRunAsGroup()
                        .longValue());
        assertTrue(
                executorTemplateSupplier
                        .get()
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getSecurityContext()
                        .getRunAsNonRoot());
    }

    @Test
    public void fromTesTaskToK8sJob() throws IOException {

        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-35605447");
        Resource inputTaskFile = new ClassPathResource("fromTesToK8s/task.json");
        TesTask inputTask;
        try (InputStream inputStream = inputTaskFile.getInputStream();
                Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            inputTask = this.objectMapper.readValue(reader, TesTask.class);
        }
        V1Job outputJob =
                this.converter.fromTesTaskToK8sJob(
                        inputTask,
                        User.builder("test-user-id")
                                .teskMemberedGroups(StringUtils.commaDelimitedListToSet("ABC,CDE"))
                                .build());
        assertEquals(outputJob.getMetadata().getAnnotations().get("tes-task-name"), "taskFull");
        // testing annotation with entire serialized task content..
        assertThat(
                outputJob.getMetadata().getAnnotations().get("json-input"),
                is(not(isEmptyOrNullString())));
        // ..comparing jsons
        JsonContentAssert annotationWithEntireTask =
                new JsonContentAssert(
                        this.getClass(),
                        outputJob.getMetadata().getAnnotations().get("json-input"));
        annotationWithEntireTask.isEqualToJson(inputTaskFile);
        // ..and one example path
        annotationWithEntireTask
                .extractingJsonPathArrayValue("@.executors[1].command")
                .startsWith("sh");
        // ..and an example path with renamed field
        annotationWithEntireTask
                .extractingJsonPathNumberValue("@.resources.cpu_cores")
                .isEqualTo(4);
        assertEquals(outputJob.getMetadata().getLabels().get("job-type"), "taskmaster");
        assertEquals(
                outputJob.getMetadata().getLabels().get("creator-user-hash"),
                "c95b3f800751698e8f935156112885cc");
        assertFalse(outputJob.getMetadata().getLabels().containsKey("creator-user-id"));
        assertEquals(
                outputJob.getMetadata().getAnnotations().get("creator-user-id"), "test-user-id");
        assertEquals(outputJob.getMetadata().getLabels().get("creator-group-name"), "ABC");
        // test of placing generated task ID
        assertEquals(outputJob.getMetadata().getName(), "task-35605447");
        assertEquals(outputJob.getSpec().getTemplate().getMetadata().getName(), "task-35605447");
        assertEquals(
                outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getName(),
                "task-35605447");

        assertEquals(
                outputJob.getSpec().getTemplate().getSpec().getServiceAccountName(),
                "custom-service-account");
        assertEquals(
                outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().get(0),
                "-f");
        assertEquals(
                outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().get(1),
                "/jsoninput/JSON_INPUT.gz");
        assertEquals(
                outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().get(3),
                "test-namespace");
        assertEquals(
                outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().get(5),
                "task-full-filer-image-name");
        assertEquals(
                outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().get(7),
                "task-full-filer-image-version");
        assertEquals(
                outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getImage(),
                "task-full-image-name:task-full-image-version");
        assertEquals(outputJob.getSpec().getTemplate().getSpec().getRestartPolicy(), "Never");

        Resource outputJobFile = new ClassPathResource("fromTesToK8s/job.json");
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

        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-35605447");
        Resource inputTaskFile = new ClassPathResource("fromTesToK8s/task.json");
        TesTask inputTask;
        try (InputStream inputStream = inputTaskFile.getInputStream();
                Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            inputTask = this.objectMapper.readValue(reader, TesTask.class);
        }
        V1Job outputJob =
                this.converter.fromTesTaskToK8sJob(
                        inputTask,
                        User.builder("test-user-id")
                                .teskMemberedGroups(StringUtils.commaDelimitedListToSet("ABC,CDE"))
                                .build());

        V1ConfigMap outputConfigMap =
                this.converter.fromTesTaskToK8sConfigMap(
                        inputTask,
                        User.builder("test-user-id")
                                .teskMemberedGroups(StringUtils.commaDelimitedListToSet("ABC,CDE"))
                                .build(),
                        outputJob);
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
        taskMasterInputJson.hasJsonPathValue("outputs[?(@.type == 'FILE')]");
        // JSONPath filter expressions sadly return array ;/
        taskMasterInputJson
                .extractingJsonPathArrayValue("outputs[?(@.type == 'FILE')].url")
                .containsExactly("/path/to/output_file.txt");
        taskMasterInputJson
                .extractingJsonPathArrayValue("outputs[?(@.type == 'DIRECTORY')].path")
                .containsExactly("/outputs/output");
        taskMasterInputJson.extractingJsonPathNumberValue("inputs.size()").isEqualTo(3);
        taskMasterInputJson
                .extractingJsonPathArrayValue("inputs[?(@.name == 'input1')].path")
                .containsExactly("/some/volume/input.txt");
        taskMasterInputJson
                .extractingJsonPathArrayValue(
                        "inputs[?(@.url == 'http://example.org/resources')].path")
                .containsExactly("/tes/volumes/input");
        taskMasterInputJson
                .extractingJsonPathArrayValue("inputs[?(@.content)].content")
                .containsExactly("aaabbbcccddd");
        taskMasterInputJson.extractingJsonPathNumberValue("volumes.size()").isEqualTo(2);
        taskMasterInputJson.extractingJsonPathArrayValue("volumes").contains("/tmp/tmp2");
        taskMasterInputJson
                .extractingJsonPathArrayValue("executors[*].metadata.annotations['tes-task-name'])")
                .containsOnly("taskFull")
                .hasSize(2);
        taskMasterInputJson
                .extractingJsonPathArrayValue("executors[*].metadata.labels['job-type']")
                .containsOnly("executor")
                .hasSize(2);
        taskMasterInputJson
                .extractingJsonPathArrayValue("executors[*].metadata.labels['taskmaster-name']")
                .containsOnly("task-35605447")
                .hasSize(2);
        taskMasterInputJson
                .extractingJsonPathArrayValue("executors[*].metadata.labels['creator-user-hash']")
                .containsOnly("c95b3f800751698e8f935156112885cc")
                .hasSize(2);
        taskMasterInputJson
                .extractingJsonPathArrayValue(
                        "executors[*].metadata.annotations['creator-user-id']")
                .containsOnly("test-user-id")
                .hasSize(2);

        taskMasterInputJson
                .extractingJsonPathStringValue("executors[0].metadata.name")
                .isEqualTo("task-35605447-ex-00");
        taskMasterInputJson
                .extractingJsonPathStringValue("executors[0].spec.template.metadata.name")
                .isEqualTo("task-35605447-ex-00");
        taskMasterInputJson
                .extractingJsonPathStringValue("executors[0].spec.template.spec.containers[0].name")
                .isEqualTo("task-35605447-ex-00");
        taskMasterInputJson
                .extractingJsonPathStringValue("executors[1].metadata.name")
                .isEqualTo("task-35605447-ex-01");
        taskMasterInputJson
                .extractingJsonPathStringValue("executors[1].spec.template.metadata.name")
                .isEqualTo("task-35605447-ex-01");
        taskMasterInputJson
                .extractingJsonPathStringValue("executors[1].spec.template.spec.containers[0].name")
                .isEqualTo("task-35605447-ex-01");

        taskMasterInputJson
                .extractingJsonPathArrayValue("executors[*].spec.template.spec.restartPolicy")
                .containsOnly("Never")
                .hasSize(2);
        taskMasterInputJson
                .extractingJsonPathArrayValue(
                        "executors[*].spec.template.spec.containers[0].resources.limits.cpu")
                .containsOnly("4")
                .hasSize(2);
        taskMasterInputJson
                .extractingJsonPathArrayValue(
                        "executors[*].spec.template.spec.containers[0].resources.requests.cpu")
                .containsOnly("4")
                .hasSize(2);
        taskMasterInputJson
                .extractingJsonPathArrayValue(
                        "executors[*].spec.template.spec.containers[0].resources.requests.memory")
                .containsOnly("16106127360")
                .hasSize(2);

        taskMasterInputJson
                .extractingJsonPathMapValue("executors[0].spec.template.spec.containers[0]")
                .containsOnlyKeys("name", "image", "command", "resources", "volumeMounts");
        taskMasterInputJson
                .extractingJsonPathMapValue("executors[1].spec.template.spec.containers[0]")
                .containsOnlyKeys(
                        "name",
                        "image",
                        "command",
                        "resources",
                        "workingDir",
                        "env",
                        "volumeMounts");
        taskMasterInputJson
                .extractingJsonPathArrayValue(
                        "executors[0].spec.template.spec.containers[0].command")
                .containsExactly("/bin/sh", "-c", "echo 'hello world' > /tmp/stdout");
        taskMasterInputJson
                .extractingJsonPathArrayValue(
                        "executors[1].spec.template.spec.containers[0].command")
                .containsExactly(
                        "/bin/sh", "-c", "sh -c 'md5sum $src' > /tes/output.txt 2> /tes/err.txt");
        taskMasterInputJson
                .extractingJsonPathStringValue(
                        "executors[0].spec.template.spec.containers[0].image")
                .isEqualTo("ubuntu");
        taskMasterInputJson
                .extractingJsonPathStringValue(
                        "executors[1].spec.template.spec.containers[0].image")
                .isEqualTo("alpine");

        taskMasterInputJson
                .extractingJsonPathArrayValue(
                        "executors[1].spec.template.spec.containers[0].env[?(@.name == 'sth')].value")
                .containsOnly("sthElse");
        taskMasterInputJson
                .extractingJsonPathStringValue(
                        "executors[1].spec.template.spec.containers[0].workingDir")
                .isEqualTo("/starthere");

        taskMasterInputJson.extractingJsonPathNumberValue("resources.disk_gb").isEqualTo(100.0);

        taskMasterInputJson.isEqualToJson(
                new ClassPathResource("fromTesToK8s/taskmaster_param.json"),
                JSONCompareMode.NON_EXTENSIBLE);
    }

    /** The filer gets path_prefix, which it removes from the matches of a pattern (TES 1.1) */
    @Test
    public void fromTesTaskToK8sConfigMap_passesPathPrefix() throws IOException {
        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-35605447");
        TesTask inputTask =
                this.objectMapper.readValue(
                        TestUtils.getFileContentFromResources("valid_tasks/tes_1_1_fields.json"),
                        TesTask.class);
        User user = User.builder("test-user-id").build();
        V1Job job = this.converter.fromTesTaskToK8sJob(inputTask, user);
        V1ConfigMap configMap = this.converter.fromTesTaskToK8sConfigMap(inputTask, user, job);
        GZIPInputStream gis =
                new GZIPInputStream(
                        new ByteArrayInputStream(configMap.getBinaryData().get("JSON_INPUT.gz")));
        BufferedReader reader = new BufferedReader(new InputStreamReader(gis, "UTF-8"));
        StringBuilder json = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            json.append(line);
        }

        JsonContentAssert taskMasterInput = new JsonContentAssert(this.getClass(), json.toString());
        taskMasterInput.extractingJsonPathStringValue("outputs[0].path").isEqualTo("/data/*.bam");
        taskMasterInput.extractingJsonPathStringValue("outputs[0].path_prefix").isEqualTo("/data");
        taskMasterInput
                .extractingJsonPathStringValue("outputs[0].url")
                .isEqualTo("s3://bucket/out");
    }

    private TaskBuilder prepareBaseTaskBuider(boolean withExecutor) throws IOException {
        TaskBuilder taskBuilder = TaskBuilder.newSingleTask();
        taskBuilder.addJob(
                this.gson.fromJson(
                        TestUtils.getFileContentFromResources("fromK8sToTes/taskmaster.json"),
                        V1Job.class));
        taskBuilder.addPodList(
                this.gson
                        .fromJson(
                                TestUtils.getFileContentFromResources(
                                        "fromK8sToTes/taskmaster_pods.json"),
                                V1PodList.class)
                        .getItems());
        if (withExecutor) {
            taskBuilder.addJob(
                    this.gson.fromJson(
                            TestUtils.getFileContentFromResources("fromK8sToTes/executor.json"),
                            V1Job.class));
            taskBuilder.addPodList(
                    this.gson
                            .fromJson(
                                    TestUtils.getFileContentFromResources(
                                            "fromK8sToTes/executor_pods.json"),
                                    V1PodList.class)
                            .getItems());
        }
        return taskBuilder;
    }

    private TaskBuilder prepareBaseTaskBuider() throws IOException {
        return prepareBaseTaskBuider(true);
    }

    private TesTask prepareBaseExpectedTask() throws IOException {
        return this.objectMapper.readValue(
                TestUtils.getFileContentFromResources("fromK8sToTes/task.json"), TesTask.class);
    }

    @Test
    public void fromK8sToTask() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        TesTask expectedTask = this.prepareBaseExpectedTask();
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), true);
        assertThat(outputTask, is(expectedTask));
    }

    /** Logs sent by the client with the task are dropped: the only task log is TESK's own. */
    @Test
    public void fromK8sToTask_ignoresStoredLogs() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        Map<String, String> annotations =
                taskBuilder.getTask().getTaskmaster().getJob().getMetadata().getAnnotations();
        String inputJson = annotations.get("json-input");
        annotations.put(
                "json-input",
                inputJson.replaceFirst(
                        "\\{",
                        "{\"logs\":[{\"logs\":[],\"outputs\":[{\"url\":\"u\",\"path\":\"p\","
                                + "\"size_bytes\":\"1\"}]}],"));
        TesTask expectedTask = this.prepareBaseExpectedTask();
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), true);
        assertThat(outputTask, is(expectedTask));
    }

    /**
     * Backend parameters stored with tasks created before TESK dropped them aren't returned: TES
     * 1.1 says that backends don't return unsupported keys (issue #145)
     */
    @Test
    public void fromK8sToTask_dropsStoredBackendParameters() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        Map<String, String> annotations =
                taskBuilder.getTask().getTaskmaster().getJob().getMetadata().getAnnotations();
        TesTask storedTask =
                this.objectMapper.readValue(
                        annotations.get(Constants.ANN_JSON_INPUT_KEY), TesTask.class);
        Map<String, String> parameters = new HashMap<>();
        parameters.put("VmSize", "Standard_D64_v3");
        storedTask.getResources().setBackendParameters(parameters);
        annotations.put(
                Constants.ANN_JSON_INPUT_KEY, this.objectMapper.writeValueAsString(storedTask));
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), true);
        assertNull(outputTask.getResources().getBackendParameters());
        assertThat(outputTask, is(this.prepareBaseExpectedTask()));
    }

    /**
     * Jobs without a status yet (just created, before the job controller synced them) give a task
     * without start or end times instead of an error (issue #126)
     */
    @Test
    public void fromK8sToTask_jobsWithoutStatus() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getTaskmaster().getJob().setStatus(null);
        taskBuilder.getTask().getExecutors().forEach(executor -> executor.getJob().setStatus(null));
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), true);
        assertNull(outputTask.getLogs().get(0).getStartTime());
        assertNull(outputTask.getLogs().get(0).getEndTime());
        outputTask
                .getLogs()
                .get(0)
                .getLogs()
                .forEach(
                        log -> {
                            assertNull(log.getStartTime());
                            assertNull(log.getEndTime());
                        });
    }

    @Test
    public void fromK8sToTask_cancelled() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder
                .getTask()
                .getTaskmaster()
                .getJob()
                .getMetadata()
                .getLabels()
                .putIfAbsent(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.CANCELING);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), true);
        assertThat(outputTask, is(expectedTask));
    }

    @Test
    public void test_status_complete() throws IOException {
        // task with successful taskmaster and executor
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(1);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.COMPLETE));
    }

    @Test
    public void cancelled() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder
                .getTask()
                .getTaskmaster()
                .getJob()
                .getMetadata()
                .getLabels()
                .putIfAbsent(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(1);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.CANCELING));
    }

    @Test
    public void test_status_complete_with_failed_taskmaster() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(1);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().failed(1);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.COMPLETE));
    }

    @Test
    public void test_status_running() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().active(1);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(0);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.RUNNING));
    }

    @Test
    public void test_status_failed_taskmaster() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().failed(1);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(0);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.SYSTEM_ERROR));
    }

    @Test
    public void test_status_failed_executor() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getExecutors().get(0).getJob().getStatus().succeeded(0);
        taskBuilder.getTask().getExecutors().get(0).getJob().getStatus().failed(1);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.EXECUTOR_ERROR));
    }

    /**
     * An ignored failure needs the outputs filer if the stored task has outputs, and none without
     * outputs (issue #159).
     */
    @Test
    public void test_status_ignored_failure_reads_outputs() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        V1Job executor = taskBuilder.getTask().getExecutors().get(0).getJob();
        executor.getStatus().succeeded(0).failed(1);
        executor.getMetadata()
                .putAnnotationsItem(
                        Constants.ANN_IGNORE_ERROR_KEY, Constants.ANN_IGNORE_ERROR_VALUE);
        assertThat(
                "stored task with outputs, no outputs filer",
                this.converter.extractStateFromK8sJobs(taskBuilder.getTask()),
                is(TesState.SYSTEM_ERROR));

        taskBuilder
                .getTask()
                .getTaskmaster()
                .getJob()
                .getMetadata()
                .getAnnotations()
                .put(Constants.ANN_JSON_INPUT_KEY, "{\"executors\": [{}]}");
        assertThat(
                "stored task without outputs",
                this.converter.extractStateFromK8sJobs(taskBuilder.getTask()),
                is(TesState.COMPLETE));
    }

    @Test
    public void test_status_complete_with_failed_executor() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getExecutors().get(0).getJob().getStatus().succeeded(1);
        taskBuilder.getTask().getExecutors().get(0).getJob().getStatus().failed(1);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.COMPLETE));
    }

    @Test
    public void test_status_complete_with_output_filer() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.addJob(
                new V1Job()
                        .status(new V1JobStatus())
                        .metadata(new V1ObjectMeta().name("sth").labels(new HashMap<>())));
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.COMPLETE));
    }

    @Test
    public void test_status_complete_with_failed_output_filer() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.addJob(
                new V1Job()
                        .status(new V1JobStatus().failed(1).succeeded(1))
                        .metadata(new V1ObjectMeta().name("sth").labels(new HashMap<>())));
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.COMPLETE));
    }

    @Test
    public void test_status_failed_output_filer() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.addJob(
                new V1Job()
                        .status(new V1JobStatus().failed(1))
                        .metadata(new V1ObjectMeta().name("sth").labels(new HashMap<>())));
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.SYSTEM_ERROR));
    }

    @Test
    public void test_status_initializing() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider(false);
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().active(1).succeeded(0);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.INITIALIZING));
    }

    @Test
    public void test_status_queued() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(0);
        taskBuilder.getTask().getTaskmaster().getPods().get(0).getStatus().phase("Pending");
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.QUEUED));
    }

    @Test
    public void test_status_executor_queued() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getLastExecutor().get().getPods().get(0).getStatus().phase("Pending");
        // a pending executor counts only while the taskmaster runs
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(0).active(1);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.QUEUED));
    }

    /**
     * The task has two executors, but only the first one's job exists (e.g., the second one's was
     * deleted): the task isn't complete, whatever the state of the first executor.
     */
    @Test
    public void test_status_missing_last_executor() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        Map<String, String> annotations =
                taskBuilder.getTask().getTaskmaster().getJob().getMetadata().getAnnotations();
        TesTask storedTask =
                this.objectMapper.readValue(
                        annotations.get(Constants.ANN_JSON_INPUT_KEY), TesTask.class);
        storedTask.addExecutorsItem(storedTask.getExecutors().get(0));
        annotations.put(
                Constants.ANN_JSON_INPUT_KEY, this.objectMapper.writeValueAsString(storedTask));
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.SYSTEM_ERROR));
    }

    /** Kubernetes 1.31+ marks a failing job with the condition FailureTarget before Failed. */
    @Test
    public void test_status_executor_failure_target() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(0).active(1);
        taskBuilder
                .getTask()
                .getExecutors()
                .get(0)
                .getJob()
                .getStatus()
                .succeeded(0)
                .addConditionsItem(new V1JobCondition().type("FailureTarget").status("True"));
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.EXECUTOR_ERROR));
    }

    @Test
    public void extractExecutorCount_fromStoredTask() throws IOException {
        V1Job taskmaster = this.prepareBaseTaskBuider().getTask().getTaskmaster().getJob();
        assertThat(this.converter.extractExecutorCount(taskmaster), is(1));
    }

    @Test
    public void extractExecutorCount_emptyExecutors() throws IOException {
        assertThat(this.executorCountOf("{\"executors\": []}"), is(0));
    }

    @Test
    public void extractExecutorCount_unknown() throws IOException {
        // not JSON, empty, a JSON value other than an object, no executors, executors not a list,
        // no stored task
        for (String stored :
                new String[] {
                    "not JSON",
                    "",
                    "  ",
                    "null",
                    "[]",
                    "{}",
                    "{\"executors\": {}}",
                    "{\"executors\": null}",
                    null
                }) {
            assertNull(stored, this.executorCountOf(stored));
        }
        V1Job taskmaster = this.prepareBaseTaskBuider().getTask().getTaskmaster().getJob();
        taskmaster.getMetadata().setAnnotations(null);
        assertNull(this.converter.extractExecutorCount(taskmaster));
    }

    /** The tags of the stored task, also with JSON null values (issue #143). */
    @Test
    public void extractTags_fromStoredTask() throws IOException {
        Map<String, String> expected = new HashMap<>();
        expected.put("WORKFLOW_ID", "cwl-01234");
        expected.put("EMPTY", "");
        expected.put("NULL", null);
        assertThat(
                this.tagsOf(
                        "{\"tags\": {\"WORKFLOW_ID\": \"cwl-01234\", \"EMPTY\": \"\", \"NULL\": null}}"),
                is(expected));
        assertThat(this.tagsOf("{\"tags\": {}}"), is(new HashMap<String, String>()));
    }

    /** Without tags, or a stored task that can't be read, there are no tags (issue #143). */
    @Test
    public void extractTags_none() throws IOException {
        for (String stored :
                new String[] {
                    "not JSON", "", "null", "[]", "{}", "{\"tags\": []}", "{\"tags\": null}", null
                }) {
            assertNull(stored, this.tagsOf(stored));
        }
        V1Job taskmaster = this.prepareBaseTaskBuider().getTask().getTaskmaster().getJob();
        taskmaster.getMetadata().setAnnotations(null);
        assertNull(this.converter.extractTags(taskmaster));
    }

    private Map<String, String> tagsOf(String storedTask) throws IOException {
        V1Job taskmaster = this.prepareBaseTaskBuider().getTask().getTaskmaster().getJob();
        taskmaster.getMetadata().getAnnotations().put(Constants.ANN_JSON_INPUT_KEY, storedTask);
        return this.converter.extractTags(taskmaster);
    }

    /**
     * Whether the stored task has outputs: unknown (null) without a readable stored task (issue
     * #159).
     */
    @Test
    public void extractHasOutputs() throws IOException {
        String executors = "\"executors\": [{}]";
        assertEquals(Boolean.TRUE, this.hasOutputsOf("{" + executors + ", \"outputs\": [{}]}"));
        assertEquals(Boolean.FALSE, this.hasOutputsOf("{" + executors + ", \"outputs\": []}"));
        assertEquals(Boolean.FALSE, this.hasOutputsOf("{" + executors + "}"));
        assertEquals(Boolean.FALSE, this.hasOutputsOf("{" + executors + ", \"outputs\": {}}"));
        assertNull(this.hasOutputsOf("not JSON"));
        assertNull(this.hasOutputsOf(null));
    }

    private Boolean hasOutputsOf(String storedTask) throws IOException {
        V1Job taskmaster = this.prepareBaseTaskBuider().getTask().getTaskmaster().getJob();
        taskmaster.getMetadata().getAnnotations().put(Constants.ANN_JSON_INPUT_KEY, storedTask);
        return this.converter.extractHasOutputs(taskmaster);
    }

    private Integer executorCountOf(String storedTask) throws IOException {
        V1Job taskmaster = this.prepareBaseTaskBuider().getTask().getTaskmaster().getJob();
        taskmaster.getMetadata().getAnnotations().put(Constants.ANN_JSON_INPUT_KEY, storedTask);
        return this.converter.extractExecutorCount(taskmaster);
    }

    @Test
    public void test_status_system_error_unknown() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getTaskmaster().getJob().getStatus().succeeded(0);
        TesState state = this.converter.extractStateFromK8sJobs(taskBuilder.getTask());
        assertThat(state, is(TesState.SYSTEM_ERROR));
    }

    /**
     * Decimal numbers of CPU cores (e.g. 0.5) are requested as such, not truncated to whole cores
     * (issue #44)
     */
    @Test
    public void fromTesExecutorToK8sJob_decimalCpuCores() throws IOException {
        TesResources resources =
                this.objectMapper.readValue("{\"cpu_cores\": 0.5}", TesResources.class);
        TesExecutor executor = new TesExecutor().image("alpine").addCommandItem("true");
        V1Job job =
                this.converter.fromTesExecutorToK8sJob(
                        "task-123",
                        "name",
                        executor,
                        0,
                        resources,
                        User.builder("test-user-id").build());
        V1ResourceRequirements requirements =
                job.getSpec().getTemplate().getSpec().getContainers().get(0).getResources();
        assertEquals("500m", requirements.getLimits().get("cpu").toSuffixedString());
        assertEquals("500m", requirements.getRequests().get("cpu").toSuffixedString());
    }

    /**
     * An executor that ignores errors gets the annotation with which the taskmaster goes on after
     * it fails; other executors get none (issue #159)
     */
    @Test
    public void fromTesExecutorToK8sJob_ignoreError() {
        Boolean[] values = {true, false, null};
        for (Boolean ignoreError : values) {
            TesExecutor executor =
                    new TesExecutor()
                            .image("alpine")
                            .addCommandItem("true")
                            .ignoreError(ignoreError);
            V1Job job =
                    this.converter.fromTesExecutorToK8sJob(
                            "task-123",
                            "name",
                            executor,
                            0,
                            null,
                            User.builder("test-user-id").build());
            String annotation = job.getMetadata().getAnnotations().get("ignore-error");
            assertEquals(
                    "ignore_error: " + ignoreError,
                    Boolean.TRUE.equals(ignoreError) ? "true" : null,
                    annotation);
        }
    }

    /**
     * RAM is requested with a decimal point whatever the JVM's default locale is, e.g. also under a
     * locale with a decimal comma
     */
    @Test
    public void fromTesExecutorToK8sJob_ramIndependentOfLocale() throws IOException {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            TesResources resources =
                    this.objectMapper.readValue("{\"ram_gb\": 1.5}", TesResources.class);
            TesExecutor executor = new TesExecutor().image("alpine").addCommandItem("true");
            V1Job job =
                    this.converter.fromTesExecutorToK8sJob(
                            "task-123",
                            "name",
                            executor,
                            0,
                            resources,
                            User.builder("test-user-id").build());
            V1ResourceRequirements requirements =
                    job.getSpec().getTemplate().getSpec().getContainers().get(0).getResources();
            // 1.5 GiB in bytes
            assertEquals("1610612736", requirements.getRequests().get("memory").toSuffixedString());
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    /**
     * Whole numbers of CPU cores are written back as integers, as the TES specification defines
     * them, also when given with an exponent; decimal numbers as given
     */
    @Test
    public void cpuCores_wholeNumbersStayIntegers() throws IOException {
        assertThat(writtenCpuCores("4").isIntegralNumber(), is(true));
        assertThat(writtenCpuCores("4").asText(), is("4"));
        assertThat(writtenCpuCores("1e3").isIntegralNumber(), is(true));
        assertThat(writtenCpuCores("1e3").asText(), is("1000"));
    }

    @Test
    public void cpuCores_decimalsAsGiven() throws IOException {
        assertThat(writtenCpuCores("0.5").decimalValue(), is(new java.math.BigDecimal("0.5")));
        assertThat(writtenCpuCores("1.25").decimalValue(), is(new java.math.BigDecimal("1.25")));
    }

    /** Reads {@code cpu_cores} into the model and returns the value as written back */
    private com.fasterxml.jackson.databind.JsonNode writtenCpuCores(String cpuCores)
            throws IOException {
        TesResources resources =
                this.objectMapper.readValue(
                        "{\"cpu_cores\": " + cpuCores + "}", TesResources.class);
        return this.objectMapper
                .readTree(this.objectMapper.writeValueAsString(resources))
                .get("cpu_cores");
    }
}
