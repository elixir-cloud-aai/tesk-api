package uk.ac.ebi.tsc.tesk.k8s.convert;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_IGNORED_BACKEND_PARAMETERS_KEY;

import com.google.gson.Gson;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uk.ac.ebi.tsc.tesk.tes.model.TesResources;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * The backend parameters of a task ({@code resources.backend_parameters}), of which TESK supports
 * none. TES 1.1 says that backends neither store nor return unsupported keys, and log a warning
 * about them: TESK removes them from the task before storing it, notes their keys (not their
 * values) in an annotation of the taskmaster's job, and returns the warning in the task log's
 * {@code system_logs} (view {@code FULL}). Tasks with {@code backend_parameters_strict} and backend
 * parameters are refused before (see {@link TesResources#isBackendParametersOptional()}).
 */
public final class IgnoredBackendParameters {

    private static final String MESSAGE =
            "Ignored backend_parameters, which TESK doesn't support: ";

    private static final Gson GSON = new Gson();

    private IgnoredBackendParameters() {}

    /**
     * Removes the backend parameters from the task
     *
     * @return their keys, sorted; empty if the task had none
     */
    public static List<String> removeFrom(TesTask task) {
        TesResources resources = task.getResources();
        if (resources == null || resources.getBackendParameters() == null) {
            return Collections.emptyList();
        }
        Map<String, String> parameters = resources.getBackendParameters();
        resources.setBackendParameters(null);
        List<String> keys = new ArrayList<>(parameters.keySet());
        Collections.sort(keys);
        return keys;
    }

    /** Notes the ignored keys in the metadata of the taskmaster's job, if there are any */
    public static void annotate(V1ObjectMeta metadata, List<String> keys) {
        if (!keys.isEmpty()) {
            metadata.putAnnotationsItem(ANN_IGNORED_BACKEND_PARAMETERS_KEY, GSON.toJson(keys));
        }
    }

    /**
     * The warning about the ignored keys; they are quoted as JSON, so that it is one line whatever
     * the keys are
     */
    public static String message(List<String> keys) {
        return MESSAGE + GSON.toJson(keys);
    }

    /** The warning for the task log, from the metadata of the taskmaster's job, if it notes keys */
    public static Optional<String> systemLog(V1ObjectMeta metadata) {
        return Optional.ofNullable(metadata.getAnnotations())
                .map(annotations -> annotations.get(ANN_IGNORED_BACKEND_PARAMETERS_KEY))
                .map(keys -> MESSAGE + keys);
    }
}
