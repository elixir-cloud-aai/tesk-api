package uk.ac.ebi.tsc.tesk.k8s.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1Secret;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.Test;

/** Reading the Kubernetes objects of clusters newer than the client */
public class KubernetesJsonTest {

    /**
     * A job with fields that the client doesn't know (as Kubernetes adds them in new versions, e.g.
     * to the status) is read, and the fields it knows are kept
     */
    @Test
    public void readsUnknownFields() {
        V1Job job =
                KubernetesJson.GSON.fromJson(
                        "{\"metadata\":{\"name\":\"task-123\",\"newField\":1},"
                                + "\"spec\":{\"template\":{},\"newSpecField\":\"x\"},"
                                + "\"status\":{\"succeeded\":1,\"newStatusField\":{\"a\":[]}}}",
                        V1Job.class);
        assertThat(job.getMetadata().getName()).isEqualTo("task-123");
        assertThat(job.getStatus().getSucceeded()).isEqualTo(1);
    }

    /** Times are read and written as Kubernetes writes them (RFC 3339, UTC) */
    @Test
    public void times() {
        V1Job job =
                KubernetesJson.GSON.fromJson(
                        "{\"metadata\":{\"creationTimestamp\":\"2018-05-31T09:21:57Z\"}}",
                        V1Job.class);
        OffsetDateTime created = job.getMetadata().getCreationTimestamp();
        assertThat(created).isEqualTo(OffsetDateTime.of(2018, 5, 31, 9, 21, 57, 0, ZoneOffset.UTC));
        assertThat(KubernetesJson.GSON.toJson(job))
                .contains("\"creationTimestamp\":\"2018-05-31T09:21:57.000000Z\"");
    }

    /**
     * The configuration of the Kubernetes client lets it deserialize responses with the same Gson
     * (it is global)
     */
    @Test
    public void installed() {
        Gson before = JSON.getGson();
        try {
            JSON.setGson(new Gson());
            new KubernetesClientConfig();
            assertThat(JSON.getGson()).isSameAs(KubernetesJson.GSON);
        } finally {
            JSON.setGson(before);
        }
    }

    /** Required fields may be missing (e.g. in test fixtures) */
    @Test
    public void missingRequiredFields() {
        V1Job job = KubernetesJson.GSON.fromJson("{\"spec\":{\"parallelism\":1}}", V1Job.class);
        assertThat(job.getSpec().getParallelism()).isEqualTo(1);
    }

    /** Binary data of Secrets and ConfigMaps (Base64), both ways */
    @Test
    public void binaryData() {
        V1Secret secret =
                KubernetesJson.GSON.fromJson("{\"data\":{\"key\":\"aGVsbG8=\"}}", V1Secret.class);
        assertThat(secret.getData().get("key")).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(KubernetesJson.GSON.toJson(secret)).contains("\"key\":\"aGVsbG8=\"");
    }
}
