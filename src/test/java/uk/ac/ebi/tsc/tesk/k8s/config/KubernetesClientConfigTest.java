package uk.ac.ebi.tsc.tesk.k8s.config;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.credentials.AccessTokenAuthentication;
import org.junit.Rule;
import org.junit.Test;

/** The clients that merge-patch jobs and pods (labelling and cancelling tasks) */
public class KubernetesClientConfigTest {

    @Rule public WireMockRule kubernetes = new WireMockRule(wireMockConfig().dynamicPort());

    private final KubernetesClientConfig config = new KubernetesClientConfig();

    /** A client with a bearer token, as from a kubeconfig file */
    private ApiClient client() {
        return new ClientBuilder()
                .setBasePath(kubernetes.baseUrl())
                .setAuthentication(new AccessTokenAuthentication("token-123"))
                .build();
    }

    /**
     * Jobs are patched with a JSON merge patch, with the credentials of the client, including the
     * "Bearer" prefix of its token
     */
    @Test
    public void patchBatchApi() throws ApiException {
        kubernetes.givenThat(
                patch(urlPathEqualTo("/apis/batch/v1/namespaces/default/jobs/task-123"))
                        .willReturn(okJson("{}")));
        BatchV1Api api = config.patchBatchApi(config.patchApiClient(client()));
        api.patchNamespacedJob("task-123", "default", new V1Patch("{}")).execute();
        kubernetes.verify(
                patchRequestedFor(urlPathEqualTo("/apis/batch/v1/namespaces/default/jobs/task-123"))
                        .withHeader("Content-Type", containing("application/merge-patch+json"))
                        .withHeader("Authorization", equalTo("Bearer token-123")));
    }

    /** Pods are patched in the same way */
    @Test
    public void patchCoreApi() throws ApiException {
        kubernetes.givenThat(
                patch(urlPathEqualTo("/api/v1/namespaces/default/pods/pod-123"))
                        .willReturn(okJson("{}")));
        CoreV1Api api = config.patchCoreApi(config.patchApiClient(client()));
        api.patchNamespacedPod("pod-123", "default", new V1Patch("{}")).execute();
        kubernetes.verify(
                patchRequestedFor(urlPathEqualTo("/api/v1/namespaces/default/pods/pod-123"))
                        .withHeader("Content-Type", containing("application/merge-patch+json"))
                        .withHeader("Authorization", equalTo("Bearer token-123")));
    }
}
