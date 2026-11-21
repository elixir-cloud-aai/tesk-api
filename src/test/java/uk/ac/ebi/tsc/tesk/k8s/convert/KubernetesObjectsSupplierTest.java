package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Capabilities;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1PodSecurityContext;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1SeccompProfile;
import io.kubernetes.client.openapi.models.V1SecurityContext;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uk.ac.ebi.tsc.tesk.k8s.config.KubernetesJson;

/**
 * The security context of the executors' pods, from the ConfigMap that the environment variables
 * {@code CONFIGMAP}, {@code CONFIGMAPNAMESPACE} and {@code CONFIGMAPKEY} name (read through
 * Spring's {@link org.springframework.core.env.Environment}, which includes the environment
 * variables), and the security contexts and resources of the taskmaster's and the executors' pods
 * from the settings {@code tesk.api.taskmaster.*}
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

    private final TaskmasterEnvProperties properties = new TaskmasterEnvProperties();

    private KubernetesObjectsSupplier supplier() throws IOException, ApiException {
        JobNameGenerator names = mock(JobNameGenerator.class);
        when(names.getTaskMasterName()).thenReturn("task-123");
        return new KubernetesObjectsSupplier(
                KubernetesJson.GSON, names, properties, "tesk", coreApi, environment);
    }

    private V1PodSecurityContext securityContext() throws IOException, ApiException {
        return executorPod().getSecurityContext();
    }

    private V1PodSpec executorPod() throws IOException, ApiException {
        return supplier().executorTemplate().getSpec().getTemplate().getSpec();
    }

    private V1PodSpec taskmasterPod() throws IOException, ApiException {
        return supplier().taskMasterTemplate().getSpec().getTemplate().getSpec();
    }

    private static final String RESTRICTED_POD =
            "{\"runAsNonRoot\": true, \"seccompProfile\": {\"type\": \"RuntimeDefault\"}}";

    private static final String RESTRICTED_CONTAINER =
            "{\"allowPrivilegeEscalation\": false, \"capabilities\": {\"drop\": [\"ALL\"]}}";

    private static final V1PodSecurityContext RESTRICTED_POD_CONTEXT =
            new V1PodSecurityContext()
                    .runAsNonRoot(true)
                    .seccompProfile(new V1SeccompProfile().type("RuntimeDefault"));

    private static final V1SecurityContext RESTRICTED_CONTAINER_CONTEXT =
            new V1SecurityContext()
                    .allowPrivilegeEscalation(false)
                    .capabilities(new V1Capabilities().addDropItem("ALL"));

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

    /**
     * {@code CONFIGMAPNAMESPACE} and {@code CONFIGMAPKEY} choose the namespace and the key, and all
     * settings of the YAML are taken over
     */
    @Test
    public void customNamespaceAndKey() throws Exception {
        environment.setProperty("CONFIGMAP", "security");
        environment.setProperty("CONFIGMAPNAMESPACE", "config");
        environment.setProperty("CONFIGMAPKEY", "executors");
        configMap(
                "security",
                "config",
                new V1ConfigMap()
                        .putDataItem("securityContext", "runAsUser: 1")
                        .putDataItem(
                                "executors",
                                "fsGroup: 2000\nrunAsUser: 2001\nrunAsGroup: 2002\nnonRoot: TRUE"));

        assertThat(securityContext())
                .isEqualTo(
                        new V1PodSecurityContext()
                                .fsGroup(2000L)
                                .runAsUser(2001L)
                                .runAsGroup(2002L)
                                .runAsNonRoot(true));
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

    /** Without settings, the taskmaster's pod has no security contexts and no resources */
    @Test
    public void taskmaster_withoutSettings() throws Exception {
        V1PodSpec pod = taskmasterPod();
        V1Container container = pod.getContainers().get(0);
        assertThat(pod.getSecurityContext()).isNull();
        assertThat(container.getSecurityContext()).isNull();
        assertThat(container.getResources()).isNull();
        // also not in the JSON of the job
        String json = KubernetesJson.GSON.toJson(supplier().taskMasterTemplate());
        assertThat(json).doesNotContain("securityContext").doesNotContain("resources");
    }

    /** The settings give the taskmaster's pod its security contexts and resources */
    @Test
    public void taskmaster_withSettings() throws Exception {
        properties.setPodSecurityContext(RESTRICTED_POD);
        properties.setSecurityContext(RESTRICTED_CONTAINER);
        properties.setResources(
                "{\"requests\": {\"cpu\": \"100m\", \"memory\": \"128Mi\"},"
                        + " \"limits\": {\"cpu\": \"500m\", \"memory\": \"256Mi\"}}");
        V1PodSpec pod = taskmasterPod();
        V1Container container = pod.getContainers().get(0);
        assertThat(pod.getSecurityContext()).isEqualTo(RESTRICTED_POD_CONTEXT);
        assertThat(container.getSecurityContext()).isEqualTo(RESTRICTED_CONTAINER_CONTEXT);
        assertThat(container.getResources())
                .isEqualTo(
                        new V1ResourceRequirements()
                                .putRequestsItem("cpu", Quantity.fromString("100m"))
                                .putRequestsItem("memory", Quantity.fromString("128Mi"))
                                .putLimitsItem("cpu", Quantity.fromString("500m"))
                                .putLimitsItem("memory", Quantity.fromString("256Mi")));
    }

    /** Every job gets objects of its own, so that changing one job doesn't change the others */
    @Test
    public void newObjectsForEveryJob() throws Exception {
        properties.setPodSecurityContext(RESTRICTED_POD);
        properties.setSecurityContext(RESTRICTED_CONTAINER);
        properties.setResources("{\"limits\": {\"cpu\": \"1\"}}");
        properties.setExecutorPodSecurityContext(RESTRICTED_POD);
        properties.setExecutorSecurityContext(RESTRICTED_CONTAINER);
        KubernetesObjectsSupplier supplier = supplier();
        V1Job first = supplier.taskMasterTemplate();
        V1Job second = supplier.taskMasterTemplate();
        first.getSpec().getTemplate().getSpec().getSecurityContext().runAsUser(1L);
        first.getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .get(0)
                .getSecurityContext()
                .privileged(true);
        first.getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .get(0)
                .getResources()
                .putLimitsItem("memory", Quantity.fromString("1Gi"));
        assertThat(second.getSpec().getTemplate().getSpec().getSecurityContext())
                .isEqualTo(RESTRICTED_POD_CONTEXT);
        assertThat(
                        second.getSpec()
                                .getTemplate()
                                .getSpec()
                                .getContainers()
                                .get(0)
                                .getSecurityContext())
                .isEqualTo(RESTRICTED_CONTAINER_CONTEXT);
        assertThat(
                        second.getSpec()
                                .getTemplate()
                                .getSpec()
                                .getContainers()
                                .get(0)
                                .getResources()
                                .getLimits())
                .containsOnlyKeys("cpu");
        V1Job executor = supplier.executorTemplate();
        executor.getSpec().getTemplate().getSpec().getSecurityContext().runAsUser(1L);
        executor.getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .get(0)
                .getSecurityContext()
                .privileged(true);
        V1PodSpec other = supplier.executorTemplate().getSpec().getTemplate().getSpec();
        assertThat(other.getSecurityContext()).isEqualTo(RESTRICTED_POD_CONTEXT);
        assertThat(other.getContainers().get(0).getSecurityContext())
                .isEqualTo(RESTRICTED_CONTAINER_CONTEXT);
    }

    /** {@code {}} sets none, as an empty setting */
    @Test
    public void emptyObjects_none() throws Exception {
        properties.setPodSecurityContext("{}");
        properties.setSecurityContext("{}");
        properties.setResources("{}");
        properties.setExecutorPodSecurityContext("{}");
        properties.setExecutorSecurityContext("{}");
        properties.setExecutorDefaultCpu("");
        V1PodSpec taskmaster = taskmasterPod();
        assertThat(taskmaster.getSecurityContext()).isNull();
        assertThat(taskmaster.getContainers().get(0).getSecurityContext()).isNull();
        assertThat(taskmaster.getContainers().get(0).getResources()).isNull();
        V1PodSpec executor = executorPod();
        assertThat(executor.getSecurityContext()).isNull();
        assertThat(executor.getContainers().get(0).getSecurityContext()).isNull();
        assertThat(executor.getContainers().get(0).getResources())
                .isEqualTo(new V1ResourceRequirements());
    }

    /**
     * Without settings, the executors' pods are as before: no security context of the container, no
     * resources (here: no ConfigMap either)
     */
    @Test
    public void executor_withoutSettings() throws Exception {
        V1PodSpec pod = executorPod();
        assertThat(pod.getSecurityContext()).isNull();
        assertThat(pod.getContainers().get(0).getSecurityContext()).isNull();
        assertThat(pod.getContainers().get(0).getResources())
                .isEqualTo(new V1ResourceRequirements());
        assertThat(
                        KubernetesJson.GSON_WITHOUT_EMPTY_COLLECTIONS.toJson(
                                supplier().executorTemplate()))
                .doesNotContain("securityContext")
                .contains("\"resources\":{}");
    }

    /** The settings give the executors' pods their security contexts, without a ConfigMap */
    @Test
    public void executor_withSettings() throws Exception {
        properties.setExecutorPodSecurityContext(RESTRICTED_POD);
        properties.setExecutorSecurityContext(RESTRICTED_CONTAINER);
        V1PodSpec pod = executorPod();
        assertThat(pod.getSecurityContext()).isEqualTo(RESTRICTED_POD_CONTEXT);
        assertThat(pod.getContainers().get(0).getSecurityContext())
                .isEqualTo(RESTRICTED_CONTAINER_CONTEXT);
    }

    /**
     * The pod security context of the setting goes on top of that of the ConfigMap: its fields
     * replace those of the ConfigMap, the others stay
     */
    @Test
    public void executor_settingOnTopOfConfigMap() throws Exception {
        environment.setProperty("CONFIGMAP", "security");
        configMap(
                "security",
                "tesk",
                new V1ConfigMap()
                        .putDataItem(
                                "securityContext",
                                "fsGroup: 2000\nrunAsUser: 2001\nrunAsGroup: 2002\nnonRoot: false"));
        properties.setExecutorPodSecurityContext(
                "{\"runAsNonRoot\": true, \"runAsUser\": 100,"
                        + " \"seccompProfile\": {\"type\": \"RuntimeDefault\"}}");
        assertThat(securityContext())
                .isEqualTo(
                        new V1PodSecurityContext()
                                .fsGroup(2000L)
                                .runAsUser(100L)
                                .runAsGroup(2002L)
                                .runAsNonRoot(true)
                                .seccompProfile(new V1SeccompProfile().type("RuntimeDefault")));
    }

    /** {@code {}} keeps the ConfigMap's security context as it is */
    @Test
    public void executor_emptySettingKeepsConfigMap() throws Exception {
        environment.setProperty("CONFIGMAP", "security");
        configMap(
                "security",
                "tesk",
                new V1ConfigMap().putDataItem("securityContext", "runAsUser: 7"));
        properties.setExecutorPodSecurityContext("{}");
        assertThat(securityContext()).isEqualTo(new V1PodSecurityContext().runAsUser(7L));
    }

    /** The default CPU is the request and the limit of every executor */
    @Test
    public void executor_defaultCpu() throws Exception {
        properties.setExecutorDefaultCpu("500m");
        assertThat(executorPod().getContainers().get(0).getResources())
                .isEqualTo(
                        new V1ResourceRequirements()
                                .putRequestsItem("cpu", Quantity.fromString("500m"))
                                .putLimitsItem("cpu", Quantity.fromString("500m")));
    }

    /** Invalid settings stop TESK API at startup, naming the setting */
    @Test
    public void invalidSettings_refused() {
        String[][] settings = {
            {"pod-security-context", "{\"runAsNonRoot\": \"yes\"}"},
            {"security-context", "{\"privileged\": 0}"},
            {"resources", "{\"limits\": {\"cpu\": \"many\"}}"},
            {"executor-pod-security-context", "[]"},
            {"executor-security-context", "{\"drop\": [\"ALL\"]}"},
            {"executor-default-cpu", "0"}
        };
        for (String[] setting : settings) {
            TaskmasterEnvProperties invalid = new TaskmasterEnvProperties();
            switch (setting[0]) {
                case "pod-security-context" -> invalid.setPodSecurityContext(setting[1]);
                case "security-context" -> invalid.setSecurityContext(setting[1]);
                case "resources" -> invalid.setResources(setting[1]);
                case "executor-pod-security-context" ->
                        invalid.setExecutorPodSecurityContext(setting[1]);
                case "executor-security-context" -> invalid.setExecutorSecurityContext(setting[1]);
                default -> invalid.setExecutorDefaultCpu(setting[1]);
            }
            assertThatThrownBy(() ->
                            new KubernetesObjectsSupplier(
                                    KubernetesJson.GSON,
                                    mock(JobNameGenerator.class),
                                    invalid,
                                    "tesk",
                                    coreApi,
                                    environment))
                    .as(setting[0])
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Invalid setting tesk.api.taskmaster." + setting[0]);
        }
    }
}
