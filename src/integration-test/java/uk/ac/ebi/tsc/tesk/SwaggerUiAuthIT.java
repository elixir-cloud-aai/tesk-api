package uk.ac.ebi.tsc.tesk;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.API_DOCS_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.HOME_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SWAGGER_UI_URLS;

/**
 * With authentication on and the default settings, the Swagger UI and its static resources are not found (not
 * unauthorized), and the API description needs no token
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = {"classpath:application.properties"},
        properties = {"security.oauth2.resource.user-info-uri = http://localhost:8090",
                "spring.profiles.active=auth"})
public class SwaggerUiAuthIT {

    @Autowired
    private MockMvc mvc;

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    @Test
    public void uiAndStaticResourcesWithoutToken_areNotFound() throws Exception {
        for (String path : SWAGGER_UI_URLS) {
            this.mvc.perform(get(path))
                    .andExpect(status().isNotFound());
        }
        this.mvc.perform(get(HOME_URL))
                .andExpect(status().isNotFound());
    }

    @Test
    public void apiDocsWithoutToken_isOk() throws Exception {
        this.mvc.perform(get(API_DOCS_URL))
                .andExpect(status().isOk());
    }
}
