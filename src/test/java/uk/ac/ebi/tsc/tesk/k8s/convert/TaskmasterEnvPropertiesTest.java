package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The optional FTP account and executor Secret of the taskmaster's settings */
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
}
