package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.model.TesCreateTaskResponse;
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

    /** The job is created first; the ConfigMap is owned by the created job. */
    @Test
    void configMapIsOwnedByTheTaskmasterJob() {
        this.converts("task-1");

        TesCreateTaskResponse response = this.service.createTask(this.task, USER);

        assertThat(response.getId()).isEqualTo("task-1");
        InOrder order = inOrder(this.client);
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

    /** If deleting the job fails too, the ConfigMap's error is passed on. */
    @Test
    void configMapErrorWinsOverDeleteError() {
        this.converts("task-1");
        KubernetesException error = kubernetesError(HttpStatus.FORBIDDEN);
        when(this.client.createConfigMap(any(V1ConfigMap.class))).thenThrow(error);
        doThrow(kubernetesError(HttpStatus.INTERNAL_SERVER_ERROR))
                .when(this.client)
                .deleteJob("task-1");

        assertThatThrownBy(() -> this.service.createTask(this.task, USER)).isSameAs(error);
    }

    /**
     * A ConfigMap whose name exists (e.g. one left behind by an older task) is a name collision:
     * the job is deleted and the task is created again with a new name.
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
}
