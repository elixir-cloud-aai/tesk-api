package uk.ac.ebi.tsc.tesk.k8s.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.V1Job;
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

    /** The client deserializes responses with the same Gson */
    @Test
    public void installed() {
        KubernetesJson.install();
        assertThat(JSON.getGson()).isSameAs(KubernetesJson.GSON);
    }
}
