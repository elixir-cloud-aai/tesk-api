package uk.ac.ebi.tsc.tesk.k8s.convert;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.*;
import static uk.ac.ebi.tsc.tesk.k8s.constant.K8sConstants.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.yaml.snakeyaml.Yaml;
import uk.ac.ebi.tsc.tesk.k8s.config.KubernetesJson;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Job;

/**
 * Templates for tasmaster's and executor's job object.
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@Configuration
public class KubernetesObjectsSupplier {

    private final Gson gson;

    private final JobNameGenerator jobNameGenerator;

    private final TaskmasterEnvProperties taskmasterEnvProperties;

    private final String namespace;

    private final V1PodSecurityContext securityContext;

    /** Security context of the taskmaster's pod (setting), or {@code null} */
    private final JsonObject taskmasterPodSecurityContext;

    /** Security context of the taskmaster's container (setting), or {@code null} */
    private final JsonObject taskmasterSecurityContext;

    /** Resources of the taskmaster's container (setting), or {@code null} */
    private final JsonObject taskmasterResources;

    /**
     * Security context of the executors' pods: that of the ConfigMap with the setting's fields on
     * top, or {@code null} if the setting is empty (then {@link #securityContext} as before)
     */
    private final JsonObject executorPodSecurityContext;

    /** Security context of the executors' containers (setting), or {@code null} */
    private final JsonObject executorSecurityContext;

    /** CPU request and limit of executors whose task sets no CPU cores, or {@code null} */
    private final Quantity executorDefaultCpu;

    /** The taskmaster's job template ({@code taskmaster.json}) */
    private final String taskmasterTemplateJson;

    private static final String envConfigMap = "CONFIGMAP";
    private static final String envConfigMapNamespace = "CONFIGMAPNAMESPACE";
    private static final String envConfigMapKey = "CONFIGMAPKEY";

    public KubernetesObjectsSupplier(
            Gson gson,
            JobNameGenerator jobNameGenerator,
            TaskmasterEnvProperties taskmasterEnvProperties,
            @Value("${tesk.api.k8s.namespace}") String namespace,
            CoreV1Api coreApi,
            Environment environment)
            throws IOException, ApiException {
        this.gson = gson;
        this.jobNameGenerator = jobNameGenerator;
        this.taskmasterEnvProperties = taskmasterEnvProperties;
        this.namespace = namespace;
        this.securityContext = createSecurityContext(coreApi, namespace, environment);
        String prefix = "tesk.api.taskmaster.";
        this.taskmasterPodSecurityContext =
                PodSettings.object(
                        prefix + "pod-security-context",
                        taskmasterEnvProperties.getPodSecurityContext(),
                        V1PodSecurityContext.class,
                        V1PodSecurityContext::validateJsonElement);
        this.taskmasterSecurityContext =
                PodSettings.object(
                        prefix + "security-context",
                        taskmasterEnvProperties.getSecurityContext(),
                        V1SecurityContext.class,
                        V1SecurityContext::validateJsonElement);
        this.taskmasterResources =
                PodSettings.resources(prefix + "resources", taskmasterEnvProperties.getResources());
        JsonObject executorPodSettings =
                PodSettings.object(
                        prefix + "executor-pod-security-context",
                        taskmasterEnvProperties.getExecutorPodSecurityContext(),
                        V1PodSecurityContext.class,
                        V1PodSecurityContext::validateJsonElement);
        this.executorPodSecurityContext =
                executorPodSettings == null
                        ? null
                        : PodSettings.merge(
                                this.securityContext == null
                                        ? null
                                        : KubernetesJson.GSON_WITHOUT_EMPTY_COLLECTIONS
                                                .toJsonTree(this.securityContext)
                                                .getAsJsonObject(),
                                executorPodSettings);
        this.executorSecurityContext =
                PodSettings.object(
                        prefix + "executor-security-context",
                        taskmasterEnvProperties.getExecutorSecurityContext(),
                        V1SecurityContext.class,
                        V1SecurityContext::validateJsonElement);
        this.executorDefaultCpu =
                PodSettings.quantity(
                        prefix + "executor-default-cpu",
                        taskmasterEnvProperties.getExecutorDefaultCpu());
        try (InputStream template = new ClassPathResource("taskmaster.json").getInputStream()) {
            this.taskmasterTemplateJson =
                    StreamUtils.copyToString(template, StandardCharsets.UTF_8);
        }
    }

    /**
     * The security context of the executors' pods, from the ConfigMap that the environment
     * variables {@code CONFIGMAP}, {@code CONFIGMAPNAMESPACE} and {@code CONFIGMAPKEY} name. They
     * are read through Spring's {@link Environment}, which includes the environment variables (and
     * lets the tests set them).
     */
    private static V1PodSecurityContext createSecurityContext(
            CoreV1Api coreApi, String default_namespace, Environment environment)
            throws ApiException {
        String confmap_name = environment.getProperty(envConfigMap);
        if (confmap_name == null) {
            return null;
        }

        String confmap_namespace =
                environment.getProperty(envConfigMapNamespace, default_namespace);
        V1ConfigMap config =
                coreApi.readNamespacedConfigMap(confmap_name, confmap_namespace).execute();

        String config_key = environment.getProperty(envConfigMapKey, "securityContext");

        V1PodSecurityContext security_context = new V1PodSecurityContext();
        if (config.getData().containsKey(config_key)) {
            String security_context_yaml = config.getData().get(config_key);
            Yaml yaml = new Yaml();
            Map<String, Object> security_context_obj = yaml.load(security_context_yaml);
            if (security_context_obj.containsKey("fsGroup")) {
                security_context.fsGroup(
                        Long.parseLong(security_context_obj.get("fsGroup").toString()));
            }
            if (security_context_obj.containsKey("runAsUser")) {
                security_context.runAsUser(
                        Long.parseLong(security_context_obj.get("runAsUser").toString()));
            }
            if (security_context_obj.containsKey("runAsGroup")) {
                security_context.runAsGroup(
                        Long.parseLong(security_context_obj.get("runAsGroup").toString()));
            }
            if (security_context_obj.containsKey("nonRoot")) {
                security_context.runAsNonRoot(
                        security_context_obj
                                .get("nonRoot")
                                .toString()
                                .toLowerCase(Locale.ROOT)
                                .equals("true"));
            }
        }
        return security_context;
    }

    /**
     * Creates a new empty taskmaster's K8s job object with auto-generated name. Uses JSON file from
     * resources as a template and adds generated name to it. Additionally, places appropriately
     * taskmaster's image name and version from parameters
     *
     * @return - new K8s Job object with auto-generated name.
     */
    @Bean
    @Scope(value = "prototype")
    public V1Job taskMasterTemplate() {
        V1Job job = gson.fromJson(this.taskmasterTemplateJson, V1Job.class);
        job.getSpec()
                .getTemplate()
                .getSpec()
                .setServiceAccountName(this.taskmasterEnvProperties.getServiceAccountName());
        V1Container taskmasterContainer =
                job.getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .get(0)
                        .image(
                                new StringJoiner(":")
                                        .add(taskmasterEnvProperties.getImageName())
                                        .add(taskmasterEnvProperties.getImageVersion())
                                        .toString())
                        .addArgsItem("-n")
                        .addArgsItem(namespace)
                        .addArgsItem("-fn")
                        .addArgsItem(this.taskmasterEnvProperties.getFilerImageName())
                        .addArgsItem("-fv")
                        .addArgsItem(this.taskmasterEnvProperties.getFilerImageVersion());

        if (this.taskmasterEnvProperties.isDebug()) {
            taskmasterContainer.addArgsItem("-d").setImagePullPolicy("Always");
        }
        // From the settings; none if they are empty, as before 2.1.0 (new objects for every job)
        job.getSpec()
                .getTemplate()
                .getSpec()
                .setSecurityContext(
                        PodSettings.fromJson(
                                this.taskmasterPodSecurityContext, V1PodSecurityContext.class));
        taskmasterContainer.setSecurityContext(
                PodSettings.fromJson(this.taskmasterSecurityContext, V1SecurityContext.class));
        taskmasterContainer.setResources(
                PodSettings.fromJson(this.taskmasterResources, V1ResourceRequirements.class));
        job.getMetadata().putLabelsItem(LABEL_JOBTYPE_KEY, LABEL_JOBTYPE_VALUE_TASKM);
        String taskMasterName = this.jobNameGenerator.getTaskMasterName();
        new Job(job).changeJobName(taskMasterName);
        Set<V1EnvVar> toBeRemoved = new HashSet<>();
        List<V1EnvVar> containerEnvironment =
                job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        containerEnvironment.addAll(
                taskmasterEnvProperties.getEnvironment().entrySet().stream()
                        .map(e ->
                                new V1EnvVar()
                                        .name(e.getKey().toUpperCase().replaceAll("\\.", "_"))
                                        .value(e.getValue()))
                        .collect(Collectors.toList()));
        containerEnvironment.stream()
                .filter(env ->
                        FTP_SECRET_USERNAME_ENV.equals(env.getName())
                                || FTP_SECRET_PASSWORD_ENV.equals(env.getName()))
                .forEach(env -> {
                    if (taskmasterEnvProperties.isFTPEnabled()) {
                        // update secret name, if FTP enabled
                        env.getValueFrom()
                                .getSecretKeyRef()
                                .setName(this.taskmasterEnvProperties.getFtp().getSecretName());
                    } else {
                        // remove FTP secrets otherwise
                        toBeRemoved.add(env);
                    }
                });

        toBeRemoved.stream().forEach(containerEnvironment::remove);
        return job;
    }

    /**
     * Creates a new empty executor's K8s job object (without a name), by initializing required
     * objects in the graph (new new new) and putting constants.
     *
     * @return
     */
    @Bean
    @Scope(value = "prototype")
    public V1Job executorTemplate() {
        V1ResourceRequirements resources = new V1ResourceRequirements();
        // The default CPU request and limit; the task's CPU cores replace them
        if (this.executorDefaultCpu != null) {
            resources
                    .putRequestsItem(RESOURCE_CPU_KEY, this.executorDefaultCpu)
                    .putLimitsItem(RESOURCE_CPU_KEY, this.executorDefaultCpu);
        }
        V1Container container =
                new V1Container()
                        .resources(resources)
                        .securityContext(
                                PodSettings.fromJson(
                                        this.executorSecurityContext, V1SecurityContext.class));
        if (this.taskmasterEnvProperties.isExecutorSecretEnabled()) {
            container.addVolumeMountsItem(
                    new V1VolumeMount()
                            .readOnly(Boolean.TRUE)
                            .name(this.taskmasterEnvProperties.getExecutorSecret().getName())
                            .mountPath(
                                    this.taskmasterEnvProperties
                                            .getExecutorSecret()
                                            .getMountPath()));
        }

        V1PodSpec podSpec =
                new V1PodSpec().addContainersItem(container).restartPolicy(JOB_RESTART_POLICY);

        if (executorPodSecurityContext != null) {
            podSpec.securityContext(
                    PodSettings.fromJson(executorPodSecurityContext, V1PodSecurityContext.class));
        } else if (securityContext != null) {
            podSpec.securityContext(securityContext);
        }

        V1Job job =
                new V1Job()
                        .apiVersion(K8S_BATCH_API_VERSION)
                        .kind(K8S_BATCH_API_JOB_TYPE)
                        .metadata(
                                new V1ObjectMeta()
                                        .putLabelsItem(LABEL_JOBTYPE_KEY, LABEL_JOBTYPE_VALUE_EXEC))
                        .
                        // A failed executor isn't retried, so that its state and logs
                        // are those of its only run; the taskmaster's environment
                        // variable EXECUTOR_BACKOFF_LIMIT overrides this
                        spec(
                                new V1JobSpec()
                                        .backoffLimit(0)
                                        .template(
                                                new V1PodTemplateSpec()
                                                        .metadata(new V1ObjectMeta())
                                                        .spec(podSpec)));
        if (this.taskmasterEnvProperties.isExecutorSecretEnabled()) {
            job.getSpec()
                    .getTemplate()
                    .getSpec()
                    .addVolumesItem(
                            new V1Volume()
                                    .name(
                                            this.taskmasterEnvProperties
                                                    .getExecutorSecret()
                                                    .getName())
                                    .secret(
                                            new V1SecretVolumeSource()
                                                    .secretName(
                                                            this.taskmasterEnvProperties
                                                                    .getExecutorSecret()
                                                                    .getName())));
        }
        return job;
    }

    @Bean(name = "taskmaster")
    public Supplier<V1Job> taskMasterSupplier() {
        return () -> taskMasterTemplate();
    }

    @Bean(name = "executor")
    public Supplier<V1Job> executorSupplier() {
        return this::executorTemplate;
    }
}
