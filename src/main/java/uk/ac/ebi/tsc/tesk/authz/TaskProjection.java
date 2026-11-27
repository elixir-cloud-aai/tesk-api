package uk.ac.ebi.tsc.tesk.authz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import uk.ac.ebi.tsc.tesk.tes.model.TesExecutor;
import uk.ac.ebi.tsc.tesk.tes.model.TesFileType;
import uk.ac.ebi.tsc.tesk.tes.model.TesInput;
import uk.ac.ebi.tsc.tesk.tes.model.TesOutput;
import uk.ac.ebi.tsc.tesk.tes.model.TesResources;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * The fixed projection of a submitted task that the decision service gets on CreateTask: {@code
 * name}, {@code tags}, {@code resources} ({@code cpu_cores}, {@code ram_gb}, {@code disk_gb},
 * {@code preemptible}, {@code zones}), {@code volumes}, {@code executors[].image}, {@code
 * inputs[].{url,path,type}} and {@code outputs[].{url,path,type}}, with URLs stripped ({@link
 * UrlStripper}). Never commands, environments, input contents or backend parameters, which may hold
 * secrets. Fields without a value, and list elements that are null, are left out.
 */
public final class TaskProjection {

    private TaskProjection() {}

    /** The projection of the task */
    public static Map<String, Object> of(TesTask task) {
        Map<String, Object> projection = new LinkedHashMap<>();
        put(projection, "name", task.getName());
        put(projection, "tags", task.getTags());
        TesResources resources = task.getResources();
        if (resources != null) {
            Map<String, Object> values = new LinkedHashMap<>();
            put(values, "cpu_cores", resources.getCpuCores());
            put(values, "ram_gb", resources.getRamGb());
            put(values, "disk_gb", resources.getDiskGb());
            put(values, "preemptible", resources.getPreemptible());
            put(values, "zones", withoutNulls(resources.getZones()));
            projection.put("resources", values);
        }
        put(projection, "volumes", withoutNulls(task.getVolumes()));
        List<Map<String, Object>> executors = new ArrayList<>();
        if (task.getExecutors() != null) {
            for (TesExecutor executor : task.getExecutors()) {
                if (executor == null) {
                    continue;
                }
                Map<String, Object> image = new LinkedHashMap<>();
                put(image, "image", executor.getImage());
                executors.add(image);
            }
        }
        projection.put("executors", executors);
        if (task.getInputs() != null) {
            List<Map<String, Object>> inputs = new ArrayList<>();
            for (TesInput input : task.getInputs()) {
                if (input != null) {
                    inputs.add(file(input.getUrl(), input.getPath(), input.getType()));
                }
            }
            projection.put("inputs", inputs);
        }
        if (task.getOutputs() != null) {
            List<Map<String, Object>> outputs = new ArrayList<>();
            for (TesOutput output : task.getOutputs()) {
                if (output != null) {
                    outputs.add(file(output.getUrl(), output.getPath(), output.getType()));
                }
            }
            projection.put("outputs", outputs);
        }
        return projection;
    }

    private static Map<String, Object> file(String url, String path, TesFileType type) {
        Map<String, Object> file = new LinkedHashMap<>();
        put(file, "url", UrlStripper.strip(url));
        put(file, "path", path);
        put(file, "type", type == null ? null : type.getValue());
        return file;
    }

    /** The list without null elements, or null for none */
    private static List<String> withoutNulls(List<String> values) {
        return values == null ? null : values.stream().filter(v -> v != null).toList();
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
