package uk.ac.ebi.tsc.tesk.k8s.config;

import com.google.gson.Gson;
import io.kubernetes.client.openapi.JSON;
import java.time.LocalDate;
import java.time.OffsetDateTime;

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

    private KubernetesJson() {}

    /**
     * Lets the Kubernetes client read responses with {@link #GSON}: its Gson is global (static),
     * shared by all API clients
     */
    public static void install() {
        JSON.setGson(GSON);
    }
}
