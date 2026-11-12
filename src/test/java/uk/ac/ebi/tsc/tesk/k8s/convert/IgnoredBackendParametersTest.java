package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.assertj.core.api.Assertions.assertThat;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.k8s.constant.Constants;
import uk.ac.ebi.tsc.tesk.tes.model.TesResources;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * TESK supports no backend parameters: TES 1.1 says that backends neither store nor return
 * unsupported keys, and log a warning (issue #145).
 */
class IgnoredBackendParametersTest {

    private static TesTask taskWith(Map<String, String> parameters) {
        return new TesTask()
                .resources(
                        new TesResources()
                                .cpuCores(java.math.BigDecimal.ONE)
                                .backendParameters(parameters));
    }

    @Test
    void removesParametersAndReturnsSortedKeys() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("VmSize", "Standard_D64_v3");
        parameters.put("Queue", "long");
        TesTask task = taskWith(parameters);

        List<String> keys = IgnoredBackendParameters.removeFrom(task);

        assertThat(keys).containsExactly("Queue", "VmSize");
        assertThat(task.getResources().getBackendParameters()).isNull();
        assertThat(task.getResources().getCpuCores()).isEqualTo(java.math.BigDecimal.ONE);
    }

    @Test
    void emptyParametersAreRemovedWithoutKeys() {
        TesTask task = taskWith(Collections.emptyMap());

        assertThat(IgnoredBackendParameters.removeFrom(task)).isEmpty();
        assertThat(task.getResources().getBackendParameters()).isNull();
    }

    @Test
    void tasksWithoutParametersOrResourcesHaveNoKeys() {
        assertThat(IgnoredBackendParameters.removeFrom(taskWith(null))).isEmpty();
        assertThat(IgnoredBackendParameters.removeFrom(new TesTask())).isEmpty();
    }

    @Test
    void annotatedKeysGiveTheSystemLog() {
        V1ObjectMeta metadata = new V1ObjectMeta();

        IgnoredBackendParameters.annotate(metadata, Arrays.asList("Queue", "VmSize"));

        assertThat(metadata.getAnnotations())
                .containsEntry(
                        Constants.ANN_IGNORED_BACKEND_PARAMETERS_KEY, "[\"Queue\",\"VmSize\"]");
        assertThat(IgnoredBackendParameters.systemLog(metadata))
                .hasValue(
                        "Ignored backend_parameters, which TESK doesn't support:"
                                + " [\"Queue\",\"VmSize\"]");
    }

    /** Keys can be any string: the message quotes them as JSON, so it stays on one line */
    @Test
    void messageQuotesKeys() {
        assertThat(IgnoredBackendParameters.message(Collections.singletonList("a\"\nb")))
                .isEqualTo(
                        "Ignored backend_parameters, which TESK doesn't support: [\"a\\\"\\nb\"]");
    }

    @Test
    void noKeysNoAnnotationNoSystemLog() {
        V1ObjectMeta metadata = new V1ObjectMeta();

        IgnoredBackendParameters.annotate(metadata, Collections.emptyList());

        assertThat(metadata.getAnnotations()).isNull();
        assertThat(IgnoredBackendParameters.systemLog(metadata)).isEqualTo(Optional.empty());
        assertThat(
                        IgnoredBackendParameters.systemLog(
                                new V1ObjectMeta().putAnnotationsItem("other", "value")))
                .isEmpty();
    }
}
