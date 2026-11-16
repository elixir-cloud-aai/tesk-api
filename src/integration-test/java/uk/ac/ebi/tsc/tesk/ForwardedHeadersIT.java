package uk.ac.ebi.tsc.tesk;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.ac.ebi.tsc.tesk.UrlConstants.API_DOCS_URL;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Behind a reverse proxy on an internal address, the server URL in the API description (which the
 * Swagger UI uses for "Try it out") follows the proxy's {@code X-Forwarded-Host} and {@code
 * X-Forwarded-Proto} headers, also outside Kubernetes (issue #192). With a server, as MockMvc
 * doesn't run Tomcat's handling of these headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=auth"
        })
public class ForwardedHeadersIT {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort private int port;

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    @Test
    public void apiDescription_followsForwardedHeaders() throws Exception {
        HttpResponse<String> description =
                CLIENT.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + API_DOCS_URL))
                                .header("X-Forwarded-Host", "tesk.example.org")
                                .header("X-Forwarded-Proto", "https")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(description.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(description.body()).path("servers").path(0).path("url").asString())
                .isEqualTo("https://tesk.example.org");
    }
}
