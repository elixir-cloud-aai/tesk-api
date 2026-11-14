package uk.ac.ebi.tsc.tesk.k8s.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EmptyDirVolumeSource;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Volume;
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

    /**
     * Without empty collections: empty lists and maps are left out, objects without fields (e.g. an
     * emptyDir volume) and non-empty collections are kept
     */
    @Test
    public void withoutEmptyCollections() {
        V1PodSpec spec =
                new V1PodSpec()
                        .addContainersItem(
                                new V1Container()
                                        .name("c")
                                        .resources(
                                                new V1ResourceRequirements()
                                                        .putRequestsItem(
                                                                "memory", new Quantity("1Gi"))))
                        .addVolumesItem(
                                new V1Volume().name("v").emptyDir(new V1EmptyDirVolumeSource()));
        assertThat(KubernetesJson.GSON.toJson(spec)).contains("\"limits\":{}");
        assertThat(KubernetesJson.GSON_WITHOUT_EMPTY_COLLECTIONS.toJson(spec))
                .isEqualTo(
                        "{\"containers\":[{\"name\":\"c\",\"resources\":{\"requests\":"
                                + "{\"memory\":\"1Gi\"}}}],"
                                + "\"volumes\":[{\"emptyDir\":{},\"name\":\"v\"}]}");
    }

    /** Without empty collections, reading is as with {@link KubernetesJson#GSON} */
    @Test
    public void withoutEmptyCollectionsReads() {
        V1PodSpec spec =
                KubernetesJson.GSON_WITHOUT_EMPTY_COLLECTIONS.fromJson(
                        "{\"containers\":[{\"name\":\"c\",\"args\":[]}],\"unknown\":1}",
                        V1PodSpec.class);
        assertThat(spec.getContainers()).hasSize(1);
        assertThat(spec.getContainers().get(0).getName()).isEqualTo("c");
        assertThat(spec.getContainers().get(0).getArgs()).isEmpty();
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
