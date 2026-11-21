package uk.ac.ebi.tsc.tesk.k8s.convert;

import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Properties affecting taskmaster's job template
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@Configuration
@ConfigurationProperties(prefix = "tesk.api.taskmaster")
public class TaskmasterEnvProperties {
    /** Taskmaster image name */
    private String imageName;

    /** Taskmaster image version */
    private String imageVersion;

    /** Filer image name */
    private String filerImageName;

    /** Filer image version */
    private String filerImageVersion;

    /** Test FTP account settings */
    private Ftp ftp;

    /** Service Account name for taskmaster */
    private String serviceAccountName;

    /**
     * If verbose (debug) mode of taskmaster is on (it passes additional flag to taskmaster and sets
     * image pull policy to Always)
     */
    private boolean debug;

    private ExecutorSecret executorSecret;

    /** Environment variables, that will be passed to taskmaster */
    private Map<String, String> environment = new HashMap<>();

    /**
     * Security context of the taskmaster's pod, a JSON object with the fields of Kubernetes'
     * PodSecurityContext; empty or {@code {}}: none
     */
    private String podSecurityContext;

    /**
     * Security context of the taskmaster's container, a JSON object with the fields of Kubernetes'
     * SecurityContext; empty or {@code {}}: none
     */
    private String securityContext;

    /**
     * Resource requests and limits of the taskmaster's container, a JSON object with the fields of
     * Kubernetes' ResourceRequirements; empty or {@code {}}: none
     */
    private String resources;

    /**
     * Security context of the executors' pods, a JSON object with the fields of Kubernetes'
     * PodSecurityContext, on top of the one from the ConfigMap {@code CONFIGMAP} (its fields
     * replace those of the ConfigMap); empty or {@code {}}: only the ConfigMap's
     */
    private String executorPodSecurityContext;

    /**
     * Security context of the executors' containers, a JSON object with the fields of Kubernetes'
     * SecurityContext; empty or {@code {}}: none
     */
    private String executorSecurityContext;

    /**
     * CPU request and limit (a Kubernetes quantity, e.g. {@code 1} or {@code 500m}) of executors
     * whose task sets no {@code resources.cpu_cores}; empty: none
     */
    private String executorDefaultCpu;

    public Ftp getFtp() {
        return ftp;
    }

    public void setFtp(Ftp ftp) {
        this.ftp = ftp;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getFilerImageName() {
        return filerImageName;
    }

    public void setFilerImageName(String filerImageName) {
        this.filerImageName = filerImageName;
    }

    public String getFilerImageVersion() {
        return filerImageVersion;
    }

    public void setFilerImageVersion(String filerImageVersion) {
        this.filerImageVersion = filerImageVersion;
    }

    public String getImageVersion() {
        return imageVersion;
    }

    public void setImageVersion(String imageVersion) {
        this.imageVersion = imageVersion;
    }

    public String getServiceAccountName() {
        return serviceAccountName;
    }

    public void setServiceAccountName(String serviceAccountName) {
        this.serviceAccountName = serviceAccountName;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public String getPodSecurityContext() {
        return podSecurityContext;
    }

    public void setPodSecurityContext(String podSecurityContext) {
        this.podSecurityContext = podSecurityContext;
    }

    public String getSecurityContext() {
        return securityContext;
    }

    public void setSecurityContext(String securityContext) {
        this.securityContext = securityContext;
    }

    public String getResources() {
        return resources;
    }

    public void setResources(String resources) {
        this.resources = resources;
    }

    public String getExecutorPodSecurityContext() {
        return executorPodSecurityContext;
    }

    public void setExecutorPodSecurityContext(String executorPodSecurityContext) {
        this.executorPodSecurityContext = executorPodSecurityContext;
    }

    public String getExecutorSecurityContext() {
        return executorSecurityContext;
    }

    public void setExecutorSecurityContext(String executorSecurityContext) {
        this.executorSecurityContext = executorSecurityContext;
    }

    public String getExecutorDefaultCpu() {
        return executorDefaultCpu;
    }

    public void setExecutorDefaultCpu(String executorDefaultCpu) {
        this.executorDefaultCpu = executorDefaultCpu;
    }

    public ExecutorSecret getExecutorSecret() {
        return executorSecret;
    }

    public void setExecutorSecret(ExecutorSecret executorSecret) {
        this.executorSecret = executorSecret;
    }

    public boolean isFTPEnabled() {
        if (this.getFtp() == null) {
            return false;
        }
        return this.getFtp().enabled;
    }

    public boolean isExecutorSecretEnabled() {
        if (this.getExecutorSecret() == null) {
            return false;
        }
        return this.getExecutorSecret().enabled;
    }

    /** Test FTP account settings */
    public static class Ftp {

        /** Name of the secret with FTP acoount credentials */
        private String secretName;

        /**
         * If FTP account enabled (based on non-emptiness of secretName) Does not check the
         * existence of real secret via API
         */
        private boolean enabled;

        public String getSecretName() {
            return secretName;
        }

        public void setSecretName(String secretName) {
            this.secretName = secretName;
            if (!StringUtils.isEmpty(this.secretName)) {
                this.enabled = true;
            }
        }
    }

    public static class ExecutorSecret {

        /**
         * Name of a secret that will be mounted as volume to each executor. The same name will be
         * used for the secret and the volume
         */
        private String name;

        /** The path where the secret will be mounted to executors */
        private String mountPath;

        private boolean enabled;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
            if (!StringUtils.isEmpty(this.name)) {
                this.enabled = true;
            }
        }

        public String getMountPath() {
            return mountPath;
        }

        public void setMountPath(String mountPath) {
            this.mountPath = mountPath;
        }
    }
}
