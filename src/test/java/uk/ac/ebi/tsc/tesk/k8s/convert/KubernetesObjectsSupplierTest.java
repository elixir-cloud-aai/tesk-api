package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1PodSecurityContext;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The security context of the executors' pods, from the ConfigMap that the environment variables
 * {@code CONFIGMAP}, {@code CONFIGMAPNAMESPACE} and {@code CONFIGMAPKEY} name (read through
 * Spring's {@link org.springframework.core.env.Environment}, which includes the environment
 * variables)
 */
public class KubernetesObjectsSupplierTest {

    private final CoreV1Api coreApi = mock(CoreV1Api.class);

    private final MockEnvironment environment = new MockEnvironment();

    /** The ConfigMap {@code name} in {@code namespace} has {@code data} */
    private void configMap(String name, String namespace, V1ConfigMap data) throws ApiException {
        CoreV1Api.APIreadNamespacedConfigMapRequest request =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap(name, namespace)).thenReturn(request);
        when(request.execute()).thenReturn(data);
    }

    private V1PodSecurityContext securityContext() throws IOException, ApiException {
        return new KubernetesObjectsSupplier(
                        new Gson(),
                        mock(JobNameGenerator.class),
                        new TaskmasterEnvProperties(),
                        "tesk",
                        coreApi,
                        environment)
                .executorTemplate()
                .getSpec()
                .getTemplate()
                .getSpec()
                .getSecurityContext();
    }

    /**
     * Without {@code CONFIGMAPNAMESPACE} and {@code CONFIGMAPKEY}, the ConfigMap is read from
     * TESK's namespace, and the key is {@code securityContext}
     */
    @Test
    public void defaultNamespaceAndKey() throws Exception {
        environment.setProperty("CONFIGMAP", "security");
        configMap(
                "security",
                "tesk",
                new V1ConfigMap().putDataItem("securityContext", "runAsUser: 1001"));

        assertThat(securityContext()).isEqualTo(new V1PodSecurityContext().runAsUser(1001L));
    }

    /** Settings that the YAML leaves out stay unset */
    @Test
    public void emptySecurityContext() throws Exception {
        environment.setProperty("CONFIGMAP", "security");
        environment.setProperty("CONFIGMAPNAMESPACE", "config");
        environment.setProperty("CONFIGMAPKEY", "executors");
        configMap("security", "config", new V1ConfigMap().putDataItem("executors", "{}"));

        assertThat(securityContext()).isEqualTo(new V1PodSecurityContext());
    }

    /** A ConfigMap without the key gives an empty security context */
    @Test
    public void configMapWithoutTheKey() throws Exception {
        environment.setProperty("CONFIGMAP", "security");
        environment.setProperty("CONFIGMAPNAMESPACE", "config");
        environment.setProperty("CONFIGMAPKEY", "executors");
        configMap("security", "config", new V1ConfigMap().putDataItem("other", "runAsUser: 1"));

        assertThat(securityContext()).isEqualTo(new V1PodSecurityContext());
    }

    /** Without {@code CONFIGMAP}, the executors' pods have no security context of TESK's */
    @Test
    public void withoutConfigMap() throws Exception {
        assertThat(securityContext()).isNull();
    }
}
