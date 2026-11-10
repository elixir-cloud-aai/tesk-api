package uk.ac.ebi.tsc.tesk.tes.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class TesExecutorTest {

    @Test
    public void equalityAndHashCodeIncludeRegistryCredentials() {
        TesExecutor first =
                new TesExecutor()
                        .image("registry.example.org/image")
                        .addCommandItem("true")
                        .dockerRegistry("registry.example.org")
                        .dockerLogin("robot")
                        .dockerPass("first-token");
        TesExecutor same =
                new TesExecutor()
                        .image("registry.example.org/image")
                        .addCommandItem("true")
                        .dockerRegistry("registry.example.org")
                        .dockerLogin("robot")
                        .dockerPass("first-token");
        TesExecutor different =
                new TesExecutor()
                        .image("registry.example.org/image")
                        .addCommandItem("true")
                        .dockerRegistry("registry.example.org")
                        .dockerLogin("robot")
                        .dockerPass("another-token");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(first.equals(different));
    }
}
