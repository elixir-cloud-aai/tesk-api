package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The optional FTP account and executor Secret of the taskmaster's settings, and the names of the
 * environment variables of the settings
 */
public class TaskmasterEnvPropertiesTest {

    /** Without their settings, neither the FTP account nor the executor Secret is used */
    @Test
    public void unset_disabled() {
        TaskmasterEnvProperties properties = new TaskmasterEnvProperties();
        assertThat(properties.isFTPEnabled()).isFalse();
        assertThat(properties.isExecutorSecretEnabled()).isFalse();
    }

    /** Settings without a name leave them disabled */
    @Test
    public void withoutName_disabled() {
        TaskmasterEnvProperties properties = new TaskmasterEnvProperties();
        properties.setFtp(new TaskmasterEnvProperties.Ftp());
        properties.setExecutorSecret(new TaskmasterEnvProperties.ExecutorSecret());
        assertThat(properties.isFTPEnabled()).isFalse();
        assertThat(properties.isExecutorSecretEnabled()).isFalse();
    }

    /** A name of the Secret enables them */
    @Test
    public void withName_enabled() {
        TaskmasterEnvProperties properties = new TaskmasterEnvProperties();
        TaskmasterEnvProperties.Ftp ftp = new TaskmasterEnvProperties.Ftp();
        ftp.setSecretName("ftp-secret");
        TaskmasterEnvProperties.ExecutorSecret secret =
                new TaskmasterEnvProperties.ExecutorSecret();
        secret.setName("executor-secret");
        properties.setFtp(ftp);
        properties.setExecutorSecret(secret);
        assertThat(properties.isFTPEnabled()).isTrue();
        assertThat(properties.isExecutorSecretEnabled()).isTrue();
    }

    /**
     * The security contexts and resources are read from the environment variables that the docs
     * name, next to the executor Secret's
     */
    @Test
    public void environmentVariables() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove("systemEnvironment");
        environment
                .getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                "systemEnvironment",
                                Map.of(
                                        "TESK_API_TASKMASTER_POD_SECURITY_CONTEXT", "a",
                                        "TESK_API_TASKMASTER_SECURITY_CONTEXT", "b",
                                        "TESK_API_TASKMASTER_RESOURCES", "c",
                                        "TESK_API_TASKMASTER_EXECUTOR_POD_SECURITY_CONTEXT", "d",
                                        "TESK_API_TASKMASTER_EXECUTOR_SECURITY_CONTEXT", "e",
                                        "TESK_API_TASKMASTER_EXECUTOR_DEFAULT_CPU", "f",
                                        "TESK_API_TASKMASTER_EXECUTOR_SECRET_NAME", "g")));
        TaskmasterEnvProperties properties =
                new Binder(ConfigurationPropertySources.get(environment))
                        .bind("tesk.api.taskmaster", TaskmasterEnvProperties.class)
                        .get();
        assertThat(properties.getPodSecurityContext()).isEqualTo("a");
        assertThat(properties.getSecurityContext()).isEqualTo("b");
        assertThat(properties.getResources()).isEqualTo("c");
        assertThat(properties.getExecutorPodSecurityContext()).isEqualTo("d");
        assertThat(properties.getExecutorSecurityContext()).isEqualTo("e");
        assertThat(properties.getExecutorDefaultCpu()).isEqualTo("f");
        assertThat(properties.getExecutorSecret().getName()).isEqualTo("g");
    }
}
