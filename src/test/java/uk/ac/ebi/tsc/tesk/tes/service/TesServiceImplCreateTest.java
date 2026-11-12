package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1Secret;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.constant.Constants;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.model.TesCreateTaskResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesResources;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * Creating a task creates the taskmaster's job first, then its ConfigMap, owned by that job, so
 * that Kubernetes deletes the ConfigMap with the job (issue #117).
 */
class TesServiceImplCreateTest {

    private static final User USER = User.builder("admin").build();

    private KubernetesClientWrapper client;
    private TesKubernetesConverter converter;
    private TesServiceImpl service;
    private final TesTask task = new TesTask();

    @BeforeEach
    void setUp() {
        this.client = mock(KubernetesClientWrapper.class);
        this.converter = mock(TesKubernetesConverter.class);
        this.service = new TesServiceImpl(this.client, this.converter);
        when(this.converter.fromTesTaskToK8sImagePullSecrets(any(TesTask.class), any(V1Job.class)))
                .thenReturn(Collections.emptyList());
        when(this.converter.fromK8sJobToTesCreateTaskResponse(any(V1Job.class)))
                .thenAnswer(
                        invocation ->
                                new TesCreateTaskResponse()
                                        .id(
                                                invocation
                                                        .<V1Job>getArgument(0)
                                                        .getMetadata()
                                                        .getName()));
    }

    /** The converter makes a job and a ConfigMap with this name; Kubernetes gives the job a UID. */
    private void converts(String name) {
        when(this.converter.fromTesTaskToK8sJob(this.task, USER))
                .thenReturn(new V1Job().metadata(new V1ObjectMeta().name(name)));
        when(this.converter.fromTesTaskToK8sConfigMap(any(TesTask.class), any(User.class), any()))
                .thenAnswer(
                        invocation ->
                                new V1ConfigMap()
                                        .metadata(
                                                new V1ObjectMeta()
                                                        .name(
                                                                invocation
                                                                        .<V1Job>getArgument(2)
                                                                        .getMetadata()
                                                                        .getName())));
        when(this.client.createJob(any(V1Job.class)))
                .thenAnswer(
                        invocation -> {
                            V1Job job = invocation.getArgument(0);
                            return new V1Job()
                                    .metadata(
                                            new V1ObjectMeta()
                                                    .name(job.getMetadata().getName())
                                                    .uid("uid-" + job.getMetadata().getName()));
                        });
    }

    private static KubernetesException kubernetesError(HttpStatus status) {
        return KubernetesException.fromApiException(new ApiException(status.value(), "error"));
    }

    private void convertsWithSecret(String name) {
        this.converts(name);
        when(this.converter.fromTesTaskToK8sImagePullSecrets(any(TesTask.class), any(V1Job.class)))
                .thenReturn(
                        Collections.singletonList(
                                new V1Secret().metadata(new V1ObjectMeta().name(name + "-ex-00"))));
    }

    @Test
    void secretIsCreatedBeforeConfigMapAndOwnedByTaskmaster() {
        this.convertsWithSecret("task-1");

        this.service.createTask(this.task, USER);

        InOrder order = inOrder(this.client);
        order.verify(this.client).checkConfigMap(any(V1ConfigMap.class));
        order.verify(this.client).checkSecret(any(V1Secret.class));
        order.verify(this.client).createJob(any(V1Job.class));
        ArgumentCaptor<V1Secret> secret = ArgumentCaptor.forClass(V1Secret.class);
        order.verify(this.client).createSecret(secret.capture());
        order.verify(this.client).createConfigMap(any(V1ConfigMap.class));
        assertThat(secret.getValue().getMetadata().getOwnerReferences())
                .singleElement()
                .satisfies(owner -> assertThat(owner.getUid()).isEqualTo("uid-task-1"));
    }

    @Test
    void refusedSecretCreatesNoJob() {
        this.convertsWithSecret("task-1");
        KubernetesException error = kubernetesError(HttpStatus.FORBIDDEN);
        doThrow(error).when(this.client).checkSecret(any(V1Secret.class));

        assertThatThrownBy(() -> this.service.createTask(this.task, USER)).isSameAs(error);

        verify(this.client, never()).createJob(any(V1Job.class));
    }

    @Test
    void jobIsDeletedIfSecretCreationFails() {
        this.convertsWithSecret("task-1");
        KubernetesException error = kubernetesError(HttpStatus.FORBIDDEN);
        when(this.client.createSecret(any(V1Secret.class))).thenThrow(error);

        assertThatThrownBy(() -> this.service.createTask(this.task, USER)).isSameAs(error);

        verify(this.client).deleteJob("task-1");
        verify(this.client, never()).createConfigMap(any(V1ConfigMap.class));
    }

    /** The job is created first; the ConfigMap is owned by the created job. */
    @Test
    void configMapIsOwnedByTheTaskmasterJob() {
        this.converts("task-1");

        TesCreateTaskResponse response = this.service.createTask(this.task, USER);

        assertThat(response.getId()).isEqualTo("task-1");
        InOrder order = inOrder(this.client);
        order.verify(this.client).checkConfigMap(any(V1ConfigMap.class));
        order.verify(this.client).createJob(any(V1Job.class));
        ArgumentCaptor<V1ConfigMap> configMap = ArgumentCaptor.forClass(V1ConfigMap.class);
        order.verify(this.client).createConfigMap(configMap.capture());
        assertThat(configMap.getValue().getMetadata().getOwnerReferences())
                .singleElement()
                .satisfies(
                        owner -> {
                            assertThat(owner.getApiVersion()).isEqualTo("batch/v1");
                            assertThat(owner.getKind()).isEqualTo("Job");
                            assertThat(owner.getName()).isEqualTo("task-1");
                            assertThat(owner.getUid()).isEqualTo("uid-task-1");
                            // blockOwnerDeletion would need the right to update the job's
                            // finalizers
                            assertThat(owner.getBlockOwnerDeletion()).isNull();
                        });
        verify(this.client, never()).deleteJob(anyString());
    }

    /**
     * If the ConfigMap can't be created, the job is deleted, so that its pod doesn't wait for the
     * ConfigMap forever, and the error is passed on.
     */
    @Test
    void jobIsDeletedIfConfigMapFails() {
        this.converts("task-1");
        KubernetesException error = kubernetesError(HttpStatus.FORBIDDEN);
        when(this.client.createConfigMap(any(V1ConfigMap.class))).thenThrow(error);

        assertThatThrownBy(() -> this.service.createTask(this.task, USER)).isSameAs(error);

        verify(this.client).deleteJob("task-1");
    }

    /**
     * If deleting the job fails too, the task isn't created again, also after a name collision (the
     * job could run with another task's ConfigMap), also if the delete's error is a 409 itself: the
     * error has the delete's error as its cause and the ConfigMap's error suppressed.
     */
    @Test
    void failedDeleteStopsRetries() {
        this.converts("task-1");
        KubernetesException conflict = kubernetesError(HttpStatus.CONFLICT);
        KubernetesException deleteError = kubernetesError(HttpStatus.CONFLICT);
        when(this.client.createConfigMap(any(V1ConfigMap.class))).thenThrow(conflict);
        doThrow(deleteError).when(this.client).deleteJob("task-1");

        assertThatThrownBy(() -> this.service.createTask(this.task, USER))
                .isNotInstanceOf(KubernetesException.class)
                .hasCause(deleteError)
                .hasSuppressedException(conflict);

        verify(this.client, times(1)).createJob(any(V1Job.class));
    }

    /**
     * The ConfigMap is checked (dry run) before the job is created: if Kubernetes would refuse it,
     * e.g. because it is too large, no job is created.
     */
    @Test
    void refusedConfigMapCreatesNoJob() {
        this.converts("task-1");
        KubernetesException error = kubernetesError(HttpStatus.UNPROCESSABLE_ENTITY);
        doThrow(error).when(this.client).checkConfigMap(any(V1ConfigMap.class));

        assertThatThrownBy(() -> this.service.createTask(this.task, USER)).isSameAs(error);

        verify(this.client, never()).createJob(any(V1Job.class));
        verify(this.client, never()).deleteJob(anyString());
    }

    /**
     * The dry run has an owner reference to the job, with a placeholder UID, so that Kubernetes
     * checks the right to set it (admission plugin OwnerReferencesPermissionEnforcement) before the
     * job exists.
     */
    @Test
    void checkedConfigMapHasPlaceholderOwner() {
        this.converts("task-1");
        List<V1OwnerReference> checkedOwners = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            checkedOwners.addAll(
                                    invocation
                                            .<V1ConfigMap>getArgument(0)
                                            .getMetadata()
                                            .getOwnerReferences());
                            return null;
                        })
                .when(this.client)
                .checkConfigMap(any(V1ConfigMap.class));

        this.service.createTask(this.task, USER);

        assertThat(checkedOwners)
                .singleElement()
                .satisfies(
                        owner -> {
                            assertThat(owner.getKind()).isEqualTo("Job");
                            assertThat(owner.getName()).isEqualTo("task-1");
                            assertThat(owner.getUid()).isNotEmpty().isNotEqualTo("uid-task-1");
                        });
    }

    /**
     * A name taken by a ConfigMap (e.g. one left behind by an older task) is found by the check,
     * before the job is created, and the task is created with a new name.
     */
    @Test
    void checkedNameCollisionRetriesBeforeCreatingJob() {
        this.converts("task-1");
        when(this.converter.fromTesTaskToK8sJob(this.task, USER))
                .thenReturn(new V1Job().metadata(new V1ObjectMeta().name("task-1")))
                .thenReturn(new V1Job().metadata(new V1ObjectMeta().name("task-2")));
        doThrow(kubernetesError(HttpStatus.CONFLICT))
                .doNothing()
                .when(this.client)
                .checkConfigMap(any(V1ConfigMap.class));

        assertThat(this.service.createTask(this.task, USER).getId()).isEqualTo("task-2");

        verify(this.client, times(1)).createJob(any(V1Job.class));
        verify(this.client, never()).deleteJob(anyString());
    }

    /**
     * A ConfigMap whose name was taken after the check is a name collision, too: the job is deleted
     * and the task is created again with a new name.
     */
    @Test
    void configMapNameCollisionRetriesWithNewName() {
        this.converts("task-1");
        when(this.converter.fromTesTaskToK8sJob(this.task, USER))
                .thenReturn(new V1Job().metadata(new V1ObjectMeta().name("task-1")))
                .thenReturn(new V1Job().metadata(new V1ObjectMeta().name("task-2")));
        when(this.client.createConfigMap(any(V1ConfigMap.class)))
                .thenThrow(kubernetesError(HttpStatus.CONFLICT))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TesCreateTaskResponse response = this.service.createTask(this.task, USER);

        assertThat(response.getId()).isEqualTo("task-2");
        verify(this.client).deleteJob("task-1");
        verify(this.client, times(2)).createJob(any(V1Job.class));
        ArgumentCaptor<V1ConfigMap> configMaps = ArgumentCaptor.forClass(V1ConfigMap.class);
        verify(this.client, times(2)).createConfigMap(configMaps.capture());
        V1OwnerReference owner =
                configMaps.getAllValues().get(1).getMetadata().getOwnerReferences().get(0);
        assertThat(owner.getUid()).isEqualTo("uid-task-2");
    }

    /** If the job can't be created, no ConfigMap is created and nothing is deleted. */
    @Test
    void jobErrorCreatesNoConfigMap() {
        this.converts("task-1");
        KubernetesException error = kubernetesError(HttpStatus.FORBIDDEN);
        when(this.client.createJob(any(V1Job.class))).thenThrow(error);

        assertThatThrownBy(() -> this.service.createTask(this.task, USER)).isSameAs(error);

        verify(this.client, never()).createConfigMap(any(V1ConfigMap.class));
        verify(this.client, never()).deleteJob(anyString());
    }

    /** A job name collision retries before anything was created; no job is deleted. */
    @Test
    void jobNameCollisionRetriesWithoutDeleting() {
        this.converts("task-1");
        when(this.client.createJob(any(V1Job.class)))
                .thenThrow(kubernetesError(HttpStatus.CONFLICT))
                .thenReturn(new V1Job().metadata(new V1ObjectMeta().name("task-1").uid("u")));

        this.service.createTask(this.task, USER);

        verify(this.client, never()).deleteJob(anyString());
        verify(this.client, times(1)).createConfigMap(any(V1ConfigMap.class));
    }

    /**
     * Backend parameters are removed before the task is converted, so that neither the job's
     * annotation nor the ConfigMap has them; the job notes the ignored keys, and a warning names
     * them, but not their values (issue #145). A retry after a name collision keeps the note.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void backendParametersAreDroppedWithWarning(CapturedOutput output) {
        Map<String, String> parameters = new TreeMap<>();
        parameters.put("VmSize", "Standard_D64_v3");
        this.task.resources(new TesResources().backendParameters(parameters));
        this.converts("task-1");
        when(this.converter.fromTesTaskToK8sJob(this.task, USER))
                .thenAnswer(
                        invocation -> {
                            assertThat(this.task.getResources().getBackendParameters()).isNull();
                            return new V1Job().metadata(new V1ObjectMeta().name("task-1"));
                        });
        when(this.client.createJob(any(V1Job.class)))
                .thenThrow(kubernetesError(HttpStatus.CONFLICT))
                .thenAnswer(invocation -> invocation.getArgument(0));

        this.service.createTask(this.task, USER);

        ArgumentCaptor<V1Job> job = ArgumentCaptor.forClass(V1Job.class);
        verify(this.client, times(2)).createJob(job.capture());
        for (V1Job created : job.getAllValues()) {
            assertThat(created.getMetadata().getAnnotations())
                    .containsEntry(Constants.ANN_IGNORED_BACKEND_PARAMETERS_KEY, "[\"VmSize\"]");
        }
        assertThat(output.getOut())
                .contains("task-1")
                .contains("Ignored backend_parameters, which TESK doesn't support: [\"VmSize\"]")
                .doesNotContain("Standard_D64_v3");
    }

    /** Without backend parameters, the job has no note and nothing is logged about them. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void noBackendParametersNoNote(CapturedOutput output) {
        this.converts("task-1");
        when(this.client.createJob(any(V1Job.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        this.service.createTask(this.task, USER);

        ArgumentCaptor<V1Job> job = ArgumentCaptor.forClass(V1Job.class);
        verify(this.client).createJob(job.capture());
        assertThat(job.getValue().getMetadata().getAnnotations()).isNull();
        assertThat(output.getOut()).doesNotContain("backend_parameters");
    }
}
