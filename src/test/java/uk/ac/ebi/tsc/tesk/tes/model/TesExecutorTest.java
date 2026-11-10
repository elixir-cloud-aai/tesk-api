package uk.ac.ebi.tsc.tesk.tes.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TesExecutorTest {

    @Test
    public void equalityAndHashCodeIncludeRegistryCredentials() {
        TesExecutor first = executor("registry.example.org", "robot", "first-token");
        TesExecutor same = executor("registry.example.org", "robot", "first-token");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(first.equals(executor("another.example.org", "robot", "first-token")));
        assertFalse(first.equals(executor("registry.example.org", "other", "first-token")));
        assertFalse(first.equals(executor("registry.example.org", "robot", "another-token")));
    }

    @Test
    public void everyRegistryCredentialIsRequired() {
        assertFalse(new TesExecutor().dockerLogin("robot").isDockerCredentialsValid());
        assertFalse(new TesExecutor().dockerPass("private-token").isDockerCredentialsValid());
        assertFalse(
                new TesExecutor()
                        .dockerRegistry("registry.example.org")
                        .dockerPass("private-token")
                        .isDockerCredentialsValid());
        assertFalse(
                new TesExecutor()
                        .dockerRegistry("registry.example.org")
                        .dockerLogin("robot")
                        .isDockerCredentialsValid());
        assertFalse(executor(" ", "robot", "private-token").isDockerRegistryValid());
    }

    @Test
    public void lonePasswordIsRedactedInDiagnosticString() {
        String diagnostic = new TesExecutor().dockerPass("private-token").toString();
        assertTrue(diagnostic.contains("docker credentials: [REDACTED]"));
        assertFalse(diagnostic.contains("private-token"));
    }

    private static TesExecutor executor(String registry, String login, String password) {
        return new TesExecutor()
                .image("registry.example.org/image")
                .addCommandItem("true")
                .dockerRegistry(registry)
                .dockerLogin(login)
                .dockerPass(password);
    }
}
