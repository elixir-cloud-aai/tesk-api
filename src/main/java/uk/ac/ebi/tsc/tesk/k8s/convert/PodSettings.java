package uk.ac.ebi.tsc.tesk.k8s.convert;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import uk.ac.ebi.tsc.tesk.k8s.config.KubernetesJson;

/**
 * Kubernetes objects (security contexts, resource requirements) and quantities from the settings of
 * the taskmaster's and the executors' pods, e.g. {@code tesk.api.taskmaster.security-context}.
 *
 * <p>Objects are JSON objects with the fields of the Kubernetes API (as in a manifest). An empty
 * setting and {@code {}} give none. Settings that aren't JSON objects, have fields that the
 * Kubernetes object doesn't know, or values of another type (e.g. {@code "true"} for {@code true},
 * {@code "1000"} for {@code 1000}) stop TESK API at startup: they would otherwise make every task
 * fail later, or be refused by the API server or its Pod Security admission.
 */
final class PodSettings {

    /** Parses one Kubernetes object, e.g. {@code V1SecurityContext} */
    interface Validator {
        /** Throws if the JSON doesn't fit the object (e.g. has an unknown field) */
        void validate(JsonElement json) throws IOException;
    }

    /**
     * {@link KubernetesJson#GSON}, but without Gson's conversions between JSON types: booleans,
     * whole numbers and strings must be given as such
     */
    private static final Gson STRICT_GSON =
            KubernetesJson.GSON
                    .newBuilder()
                    .registerTypeAdapter(
                            Boolean.class,
                            (JsonDeserializer<Boolean>)
                                    (json, type, context) ->
                                            primitive(json, JsonPrimitive::isBoolean, "a boolean")
                                                    .getAsBoolean())
                    // Whole numbers only: longValueExact() refuses 1.5, which Gson would truncate
                    .registerTypeAdapter(
                            Long.class,
                            (JsonDeserializer<Long>)
                                    (json, type, context) ->
                                            primitive(
                                                            json,
                                                            JsonPrimitive::isNumber,
                                                            "a whole number")
                                                    .getAsBigDecimal()
                                                    .longValueExact())
                    .registerTypeAdapter(
                            String.class,
                            (JsonDeserializer<String>)
                                    (json, type, context) ->
                                            primitive(json, JsonPrimitive::isString, "a string")
                                                    .getAsString())
                    .create();

    /** Jackson, which refuses duplicate names and content after the value */
    private static final JsonMapper STRICT_JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    /** Fields of security contexts with user or group IDs */
    private static final List<String> ID_FIELDS = List.of("runAsUser", "runAsGroup", "fsGroup");

    /** The types of seccomp profiles that Kubernetes knows */
    private static final Set<String> SECCOMP_TYPES =
            Set.of("RuntimeDefault", "Localhost", "Unconfined");

    private PodSettings() {}

    /**
     * Checks values of security contexts that the API server would refuse: user and group IDs
     * outside 0 to 2147483647, and unknown seccomp profiles (types checked before)
     */
    private static void checkValues(JsonObject json) {
        // null is valid for every field: Kubernetes treats it as unset (and it drops a field of
        // the ConfigMap's security context in the merge)
        ID_FIELDS.stream()
                .filter(field -> present(json, field))
                .forEach(field -> checkId(field, json.get(field)));
        if (present(json, "supplementalGroups")) {
            json.getAsJsonArray("supplementalGroups").asList().stream()
                    .filter(id -> !id.isJsonNull())
                    .forEach(id -> checkId("supplementalGroups", id));
        }
        if (present(json, "seccompProfile")) {
            JsonObject profile = json.getAsJsonObject("seccompProfile");
            // The model requires the type
            String profileType = profile.get("type").getAsString();
            if (!SECCOMP_TYPES.contains(profileType)) {
                throw new IllegalArgumentException(
                        "seccompProfile.type must be one of " + SECCOMP_TYPES);
            }
            if ("Localhost".equals(profileType) != present(profile, "localhostProfile")) {
                throw new IllegalArgumentException(
                        "seccompProfile.localhostProfile must be set with the type Localhost only");
            }
        }
    }

    /** If the object has the field with a value other than null */
    private static boolean present(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull();
    }

    /** Checks a user or group ID (a whole number, checked before) */
    private static void checkId(String field, JsonElement id) {
        long value = id.getAsLong();
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be between 0 and 2147483647");
        }
    }

    /**
     * A JSON object of a setting, checked against a Kubernetes object.
     *
     * @param name the setting, for errors (e.g. {@code tesk.api.taskmaster.security-context})
     * @param value the setting's value
     * @param type the Kubernetes object (e.g. {@code V1SecurityContext.class})
     * @param validator the object's check of its fields (e.g. {@code
     *     V1SecurityContext::validateJsonElement})
     * @return the JSON object, or {@code null} for an empty setting and for {@code {}}
     * @throws IllegalArgumentException if the value isn't a valid JSON object of the type
     */
    static JsonObject object(String name, String value, Class<?> type, Validator validator) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        JsonObject json;
        try {
            // Strict JSON (Gson's parser takes unquoted names, and the last of duplicate names)
            JsonElement element = JsonParser.parseString(STRICT_JSON.readTree(value).toString());
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            json = element.getAsJsonObject();
            validator.validate(json);
            // Also checks the types of the values (and quantities)
            STRICT_GSON.fromJson(json, type);
            checkValues(json);
        } catch (IOException | RuntimeException e) {
            throw invalid(name, type.getSimpleName(), e);
        }
        return json.isEmpty() ? null : json;
    }

    /**
     * Resource requirements whose requests aren't above their limits, from a setting (see {@link
     * #object})
     */
    static JsonObject resources(String name, String value) {
        JsonObject json =
                object(
                        name,
                        value,
                        V1ResourceRequirements.class,
                        V1ResourceRequirements::validateJsonElement);
        if (json != null) {
            V1ResourceRequirements resources = fromJson(json, V1ResourceRequirements.class);
            // JSON null (unset for Kubernetes) gives null maps and values
            Map<String, Quantity> limits =
                    Objects.requireNonNullElse(resources.getLimits(), Map.of());
            Map<String, Quantity> requests =
                    Objects.requireNonNullElse(resources.getRequests(), Map.of());
            Stream.concat(limits.values().stream(), requests.values().stream())
                    .filter(Objects::nonNull)
                    .filter(quantity -> quantity.getNumber().signum() < 0)
                    .findAny()
                    .ifPresent(quantity -> {
                        throw new IllegalArgumentException(
                                "Invalid setting " + name + ": negative quantity");
                    });
            requests.forEach((resource, request) -> {
                Quantity limit = limits.get(resource);
                if (request != null
                        && limit != null
                        && request.getNumber().compareTo(limit.getNumber()) > 0) {
                    throw new IllegalArgumentException(
                            "Invalid setting "
                                    + name
                                    + ": the request of "
                                    + resource
                                    + " is above its limit");
                }
            });
        }
        return json;
    }

    /**
     * A positive quantity (e.g. {@code 1}, {@code 500m}) from a setting.
     *
     * @param name the setting, for errors
     * @param value the setting's value
     * @return the quantity, or {@code null} for an empty setting
     * @throws IllegalArgumentException if the value isn't a positive quantity
     */
    static Quantity quantity(String name, String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        Quantity quantity;
        try {
            quantity = Quantity.fromString(value.trim());
        } catch (RuntimeException e) {
            throw invalid(name, "quantity", e);
        }
        if (quantity.getNumber().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Invalid setting " + name + ": not a positive quantity");
        }
        return quantity;
    }

    /**
     * The fields of {@code base}, replaced and added to by those of {@code overrides} (a shallow
     * merge: an object of {@code overrides}, e.g. {@code seccompProfile}, replaces that of {@code
     * base} as a whole). Either may be {@code null}.
     */
    static JsonObject merge(JsonObject base, JsonObject overrides) {
        if (base == null) {
            return overrides;
        }
        JsonObject merged = base.deepCopy();
        if (overrides != null) {
            overrides.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue().deepCopy()));
        }
        return merged;
    }

    /** A new object from a JSON object of a setting; {@code null} for {@code null} */
    static <T> T fromJson(JsonObject json, Class<T> type) {
        return json == null ? null : KubernetesJson.GSON.fromJson(json, type);
    }

    private static IllegalArgumentException invalid(String name, String type, Exception e) {
        // The messages of Gson and the Kubernetes models name the field, not the setting's value
        return new IllegalArgumentException(
                "Invalid setting " + name + ": not a valid " + type + " (" + e.getMessage() + ")",
                e);
    }

    /**
     * The JSON value as a primitive of the expected kind (e.g. {@link JsonPrimitive#isBoolean})
     *
     * @throws JsonParseException if it is another one, or not a primitive
     */
    private static JsonPrimitive primitive(
            JsonElement json, Predicate<JsonPrimitive> expected, String kind) {
        if (!json.isJsonPrimitive() || !expected.test(json.getAsJsonPrimitive())) {
            throw new JsonParseException("expected " + kind + ", not " + json);
        }
        return json.getAsJsonPrimitive();
    }
}
