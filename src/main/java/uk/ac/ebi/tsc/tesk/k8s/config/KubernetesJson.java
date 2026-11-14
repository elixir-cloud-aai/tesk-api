package uk.ac.ebi.tsc.tesk.k8s.config;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.kubernetes.client.openapi.JSON;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * Gson for the objects of the Kubernetes API that ignores fields it doesn't know, as the Kubernetes
 * client did up to version 19 (and as client-go does).
 *
 * <p>The models of the Kubernetes client since version 20 refuse JSON with fields that they don't
 * know, so every object that a newer Kubernetes version extends (e.g. with a field of a job's or
 * pod's status) would make reading it fail (https://github.com/kubernetes-client/java/issues/4941).
 * TESK API reads jobs, pods, ConfigMaps and LimitRanges, and only creates objects from its own
 * templates and patches them with its own patches, so ignoring unknown fields loses nothing. It
 * doesn't check required fields either. Everything else is as in the client's Gson: times (RFC
 * 3339), byte arrays (Base64), and the client's handling of Status objects and managed fields.
 */
public final class KubernetesJson {

    /** The client's Gson, for its adapters of times and byte arrays */
    private static final Gson CLIENT_GSON = JSON.getGson();

    public static final Gson GSON =
            JSON.createGson()
                    .registerTypeAdapter(
                            OffsetDateTime.class, CLIENT_GSON.getAdapter(OffsetDateTime.class))
                    .registerTypeAdapter(LocalDate.class, CLIENT_GSON.getAdapter(LocalDate.class))
                    .registerTypeAdapter(byte[].class, CLIENT_GSON.getAdapter(byte[].class))
                    .create();

    /**
     * {@link #GSON}, but leaving out lists and maps that are empty, as the Kubernetes client did up
     * to version 19: its models now start with empty lists and maps instead of none. For the
     * executors' jobs in the taskmaster's input, as the taskmaster tells "no limits" (which it sets
     * to the requests) from limits by whether the key is there. Objects without fields (e.g. {@code
     * emptyDir: {}}) are kept.
     */
    public static final Gson GSON_WITHOUT_EMPTY_COLLECTIONS =
            GSON.newBuilder().registerTypeAdapterFactory(new WithoutEmptyCollections()).create();

    private KubernetesJson() {}

    /** Writes empty lists and maps as null, which Gson leaves out of objects */
    private static final class WithoutEmptyCollections implements TypeAdapterFactory {

        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            Class<?> raw = type.getRawType();
            if (!Collection.class.isAssignableFrom(raw) && !Map.class.isAssignableFrom(raw)) {
                return null;
            }
            TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            return new TypeAdapter<T>() {
                @Override
                public void write(JsonWriter out, T value) throws IOException {
                    if (value instanceof Collection && ((Collection<?>) value).isEmpty()
                            || value instanceof Map && ((Map<?, ?>) value).isEmpty()) {
                        out.nullValue();
                    } else {
                        delegate.write(out, value);
                    }
                }

                @Override
                public T read(JsonReader in) throws IOException {
                    return delegate.read(in);
                }
            };
        }
    }

    /**
     * Lets the Kubernetes client read responses with {@link #GSON}: its Gson is global (static),
     * shared by all API clients
     */
    public static void install() {
        JSON.setGson(GSON);
    }
}
