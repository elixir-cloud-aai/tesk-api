package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.constant.Constants;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Task;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;
import uk.ac.ebi.tsc.tesk.tes.model.TesTaskLog;

/**
 * With the view {@code FULL}, the task log's {@code system_logs} has the warning about the backend
 * parameters that TESK ignored (issue #145), after the taskmaster's log.
 */
class TesServiceImplSystemLogsTest {

    private static final User USER = User.builder("admin").build();
    private static final String TASK_ID = "task-1";

    private KubernetesClientWrapper client;
    private TesKubernetesConverter converter;
    private TesServiceImpl service;
    private V1Job taskmaster;

    @BeforeEach
    void setUp() {
        this.client = mock(KubernetesClientWrapper.class);
        this.converter = mock(TesKubernetesConverter.class);
        this.service = new TesServiceImpl(this.client, this.converter);
        this.taskmaster =
                new V1Job()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK_ID)
                                        .putLabelsItem(
                                                Constants.LABEL_JOBTYPE_KEY,
                                                Constants.LABEL_JOBTYPE_VALUE_TASKM))
                        .spec(
                                new V1JobSpec()
                                        .selector(
                                                new V1LabelSelector()
                                                        .putMatchLabelsItem("job-name", TASK_ID)));
        V1Pod taskmasterPod =
                new V1Pod()
                        .metadata(
                                new V1ObjectMeta()
                                        .name(TASK_ID + "-pod")
                                        .putLabelsItem("job-name", TASK_ID));
        when(this.client.readTaskmasterJob(TASK_ID)).thenReturn(this.taskmaster);
        when(this.client.listSingleTaskExecutorJobs(TASK_ID)).thenReturn(new V1JobList());
        when(this.client.listSingleJobPods(this.taskmaster))
                .thenReturn(new V1PodList().addItemsItem(taskmasterPod));
        when(this.client.getSingleTaskOutputFilerJob(TASK_ID)).thenReturn(Optional.empty());
        when(this.client.readPodLog(anyString())).thenReturn("taskmaster log");
        when(this.converter.fromK8sJobsToTesTaskBasic(any(Task.class), anyBoolean()))
                .thenAnswer(invocation -> new TesTask().id(TASK_ID).addLogsItem(new TesTaskLog()));
    }

    @Test
    void fullViewHasWarningAfterTaskmasterLog() {
        this.taskmaster
                .getMetadata()
                .putAnnotationsItem(Constants.ANN_IGNORED_BACKEND_PARAMETERS_KEY, "[\"VmSize\"]");

        TesTask task = this.service.getTask(TASK_ID, TaskView.FULL, USER);

        assertThat(task.getLogs().get(0).getSystemLogs())
                .containsExactly(
                        "taskmaster log",
                        "Ignored backend_parameters, which TESK doesn't support: [\"VmSize\"]");
    }

    @Test
    void fullViewWithoutIgnoredParametersHasOnlyTaskmasterLog() {
        TesTask task = this.service.getTask(TASK_ID, TaskView.FULL, USER);

        assertThat(task.getLogs().get(0).getSystemLogs()).containsExactly("taskmaster log");
    }

    @Test
    void basicViewHasNoSystemLogs() {
        this.taskmaster
                .getMetadata()
                .putAnnotationsItem(Constants.ANN_IGNORED_BACKEND_PARAMETERS_KEY, "[\"VmSize\"]");

        TesTask task = this.service.getTask(TASK_ID, TaskView.BASIC, USER);

        assertThat(task.getLogs().get(0).getSystemLogs()).isNullOrEmpty();
    }
}
