package uk.ac.ebi.tsc.tesk;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.ac.ebi.tsc.tesk.UrlConstants.API_DOCS_ALIAS_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.API_DOCS_URL;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API description is also served at its former path ({@code /v2/api-docs}, Springfox until
 * 2.0.0), as the same OpenAPI 3 description as at {@code /v3/api-docs}, also without a token with
 * authentication on. With a server, as MockMvc doesn't follow the forward.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=auth"
        })
public class ApiDocsAliasIT {

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

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void alias_servesTheApiDescription() throws Exception {
        HttpResponse<String> description = get(API_DOCS_URL);
        HttpResponse<String> alias = get(API_DOCS_ALIAS_URL);

        assertThat(description.statusCode()).isEqualTo(200);
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(alias.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        JsonNode aliasJson = JSON.readTree(alias.body());
        assertThat(aliasJson.path("openapi").asString()).startsWith("3.");
        assertThat(aliasJson.path("paths").has("/ga4gh/tes/v1/tasks")).isTrue();
        assertThat(aliasJson).isEqualTo(JSON.readTree(description.body()));
    }
}
