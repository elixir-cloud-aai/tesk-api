package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.openapi.models.V1PodSecurityContext;
import io.kubernetes.client.openapi.models.V1SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Kubernetes objects and quantities from the settings of the taskmaster's and the executors' pods
 */
public class PodSettingsTest {

    private static final String NAME = "tesk.api.taskmaster.security-context";

    private static JsonObject securityContext(String value) {
        return PodSettings.object(
                NAME, value, V1SecurityContext.class, V1SecurityContext::validateJsonElement);
    }

    private static JsonObject podSecurityContext(String value) {
        return PodSettings.object(
                NAME, value, V1PodSecurityContext.class, V1PodSecurityContext::validateJsonElement);
    }

    private static JsonObject json(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Empty settings and {@code {}} give no object */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "{}", " { } "})
    public void emptyOrEmptyObject_none(String value) {
        assertThat(securityContext(value)).isNull();
        assertThat(PodSettings.resources(NAME, value)).isNull();
    }

    /** The restricted set of the Pod Security Standards is taken as given */
    @Test
    public void restricted() {
        String container =
                "{\"allowPrivilegeEscalation\": false, \"capabilities\": {\"drop\": [\"ALL\"]}}";
        assertThat(securityContext(container)).isEqualTo(json(container));
        assertThat(PodSettings.fromJson(securityContext(container), V1SecurityContext.class))
                .isEqualTo(
                        new V1SecurityContext()
                                .allowPrivilegeEscalation(false)
                                .capabilities(
                                        new io.kubernetes.client.openapi.models.V1Capabilities()
                                                .addDropItem("ALL")));
        String pod =
                "{\"runAsNonRoot\": true, \"runAsUser\": 100,"
                        + " \"seccompProfile\": {\"type\": \"RuntimeDefault\"}}";
        assertThat(PodSettings.fromJson(podSecurityContext(pod), V1PodSecurityContext.class))
                .isEqualTo(
                        new V1PodSecurityContext()
                                .runAsNonRoot(true)
                                .runAsUser(100L)
                                .seccompProfile(
                                        new io.kubernetes.client.openapi.models.V1SeccompProfile()
                                                .type("RuntimeDefault")));
    }

    /**
     * Values that aren't JSON objects, unknown fields (also snake_case ones, which the API server
     * would drop) and values of other types stop TESK API at startup, naming the setting
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "{\"runAsNonRoot\": true",
                "[]",
                "true",
                "\"runAsNonRoot\"",
                "{\"runAsRoot\": true}",
                "{\"run_as_non_root\": true}",
                "{\"runAsNonRoot\": \"true\"}",
                "{\"runAsNonRoot\": \"yes\"}",
                "{\"runAsUser\": \"1000\"}",
                "{\"runAsUser\": 1.5}",
                "{\"runAsUser\": true}",
                "{\"runAsUser\": {}}",
                "{runAsNonRoot: true}",
                "{} x",
                "{\"runAsNonRoot\": true} {}",
                "{'runAsNonRoot': true}",
                "{\"runAsNonRoot\": true, \"runAsNonRoot\": false}",
                "{\"runAsUser\": -1}",
                "{\"runAsGroup\": 2147483648}",
                "{\"fsGroup\": 4294967296}",
                "{\"supplementalGroups\": [1, -2]}",
                "{\"seccompProfile\": {\"type\": \"Bogus\"}}",
                "{\"seccompProfile\": {}}",
                "{\"seccompProfile\": {\"type\": \"Localhost\"}}",
                "{\"supplementalGroups\": [1, null]}",
                "{\"seccompProfile\": {\"type\": \"Localhost\", \"localhostProfile\": \"\"}}",
                "{\"seccompProfile\": {\"type\": \"RuntimeDefault\", \"localhostProfile\": \"\"}}",
                "{\"seccompProfile\": {\"type\": \"Localhost\", \"localhostProfile\": null}}",
                "{\"seccompProfile\": {\"type\": \"RuntimeDefault\", \"localhostProfile\": \"p\"}}",
                "{\"runAsNonRoot\": {}}",
                "{\"seccompProfile\": \"RuntimeDefault\"}",
                "{\"seccompProfile\": {\"type\": 1}}",
                "{\"seccompProfile\": {\"kind\": \"RuntimeDefault\"}}",
                "{\"supplementalGroups\": [\"a\"]}"
            })
    public void invalidPodSecurityContext_refused(String value) {
        assertThatThrownBy(() -> podSecurityContext(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid setting " + NAME + ": not a valid ");
    }

    /** IDs from 0 to 2147483647 and the seccomp profiles of Kubernetes are taken */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"runAsUser\": 0, \"runAsGroup\": 2147483647, \"fsGroup\": 100}",
                "{\"supplementalGroups\": [0, 1000]}",
                "{\"seccompProfile\": {\"type\": \"Unconfined\"}}",
                "{\"fsGroup\": null, \"runAsUser\": null, \"seccompProfile\": null}",
                "{\"supplementalGroups\": null}",
                "{\"seccompProfile\": {\"type\": \"RuntimeDefault\", \"localhostProfile\": null}}",
                "{\"seccompProfile\": {\"type\": \"Localhost\", \"localhostProfile\": \"p.json\"}}"
            })
    public void validValues(String value) {
        assertThat(podSecurityContext(value)).isEqualTo(json(value));
        if (!value.contains("Groups") && !value.contains("fsGroup")) {
            assertThat(securityContext(value)).isEqualTo(json(value));
        }
    }

    /** The same for the container's security context */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"allowPrivilegeEscalation\": \"false\"}",
                "{\"capabilities\": {\"drop\": \"ALL\"}}",
                "{\"capabilities\": {\"remove\": [\"ALL\"]}}",
                "{\"runAsNonRoot\": 1}",
                "{\"runAsUser\": -1}",
                "{\"runAsGroup\": 2147483648}",
                "{\"seccompProfile\": {\"type\": \"Bogus\"}}",
                "{\"privileged\": false, \"privileged\": true}"
            })
    public void invalidSecurityContext_refused(String value) {
        assertThatThrownBy(() -> securityContext(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(
                        "Invalid setting " + NAME + ": not a valid V1SecurityContext");
    }

    /** Resources with quantities as strings or numbers, requests at most the limits */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"requests\": {\"cpu\": \"100m\", \"memory\": \"128Mi\"},"
                        + " \"limits\": {\"cpu\": \"500m\", \"memory\": \"256Mi\"}}",
                "{\"requests\": {\"cpu\": \"1\"}, \"limits\": {\"cpu\": 1}}",
                "{\"limits\": {\"cpu\": \"1\"}}",
                "{\"requests\": {\"memory\": \"1Gi\"}, \"limits\": {\"cpu\": \"1\"}}",
                "{\"limits\": null, \"requests\": {\"cpu\": \"1\"}}",
                "{\"requests\": null}",
                "{\"requests\": {\"cpu\": null}, \"limits\": {\"cpu\": \"1\"}}",
                "{\"requests\": {\"cpu\": \"1\"}, \"limits\": {\"cpu\": null}}",
                "{\"requests\": {\"cpu\": \"0\"}}"
            })
    public void resources(String value) {
        assertThat(PodSettings.resources(NAME, value)).isEqualTo(json(value));
    }

    /** Invalid quantities, unknown fields and requests above limits are refused */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"requests\": {\"cpu\": \"lots\"}}",
                "{\"limits\": {\"memory\": \"1Gb\"}}",
                "{\"limit\": {\"cpu\": \"1\"}}",
                "{\"limits\": [\"cpu\"]}",
                "{\"requests\": {\"cpu\": \"2\"}, \"limits\": {\"cpu\": \"1\"}}",
                "{\"requests\": {\"cpu\": \"-1\"}}",
                "{\"limits\": {\"cpu\": \"-500m\"}}",
                "{\"requests\": {\"memory\": \"512Mi\"}, \"limits\": {\"memory\": \"256Mi\"}}"
            })
    public void invalidResources_refused(String value) {
        assertThatThrownBy(() -> PodSettings.resources(NAME, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid setting " + NAME + ": ");
    }

    /** Quantities: positive, trimmed; empty gives none */
    @Test
    public void quantity() {
        assertThat(PodSettings.quantity(NAME, null)).isNull();
        assertThat(PodSettings.quantity(NAME, " ")).isNull();
        assertThat(PodSettings.quantity(NAME, "1").toSuffixedString()).isEqualTo("1");
        assertThat(PodSettings.quantity(NAME, " 500m ").toSuffixedString()).isEqualTo("500m");
        assertThat(PodSettings.quantity(NAME, "0.25").toSuffixedString()).isEqualTo("250m");
    }

    /** Quantities that aren't valid or positive are refused */
    @ParameterizedTest
    @ValueSource(strings = {"one", "1 cpu", "0", "-1", "0m"})
    public void invalidQuantity_refused(String value) {
        assertThatThrownBy(() -> PodSettings.quantity(NAME, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid setting " + NAME + ": not a ");
    }

    /** The overrides' fields replace and add to those of the base, objects as a whole */
    @Test
    public void merge() {
        JsonObject base =
                json(
                        "{\"runAsUser\": 1000, \"fsGroup\": 1000, \"seLinuxOptions\": {\"level\": \"a\"}}");
        JsonObject overrides =
                json(
                        "{\"runAsUser\": 100, \"seLinuxOptions\": {\"type\": \"b\"}, \"runAsNonRoot\": true}");
        assertThat(PodSettings.merge(base, overrides))
                .isEqualTo(
                        json(
                                "{\"runAsUser\": 100, \"fsGroup\": 1000, \"seLinuxOptions\":"
                                        + " {\"type\": \"b\"}, \"runAsNonRoot\": true}"));
        // Neither is changed
        assertThat(base.get("runAsUser").getAsLong()).isEqualTo(1000L);
        assertThat(PodSettings.merge(null, overrides)).isSameAs(overrides);
        assertThat(PodSettings.merge(base, null)).isEqualTo(base);
    }

    /** No object for no JSON */
    @Test
    public void fromJson_null() {
        assertThat(PodSettings.fromJson(null, V1SecurityContext.class)).isNull();
    }
}
