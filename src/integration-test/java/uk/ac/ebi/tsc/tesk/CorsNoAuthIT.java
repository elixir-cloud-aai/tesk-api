package uk.ac.ebi.tsc.tesk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SERVICE_INFO_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** CORS without authentication, with allowed origins configured: as with authentication */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "spring.profiles.active=noauth",
            "tesk.api.cors.allowed-origins=https://client.example.org"
        })
public class CorsNoAuthIT {

    @Autowired private MockMvc mvc;

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    @Test
    public void preflight_allowedOrigin_isOk() throws Exception {
        this.mvc
                .perform(
                        options(TASK_URL)
                                .header("Origin", CorsAuthIT.ALLOWED_ORIGIN)
                                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("Access-Control-Allow-Origin", CorsAuthIT.ALLOWED_ORIGIN));
    }

    @Test
    public void preflight_otherOrigin_isForbidden() throws Exception {
        this.mvc
                .perform(
                        options(TASK_URL)
                                .header("Origin", CorsAuthIT.OTHER_ORIGIN)
                                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    public void request_allowedOrigin_isOkWithCorsHeaders() throws Exception {
        this.mvc
                .perform(get(SERVICE_INFO_URL).header("Origin", CorsAuthIT.ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("Access-Control-Allow-Origin", CorsAuthIT.ALLOWED_ORIGIN));
    }
}
