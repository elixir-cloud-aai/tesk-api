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
import static uk.ac.ebi.tsc.tesk.SwaggerUiPaths.UI_AND_STATIC_RESOURCES;

/**
 * With authentication on and {@code tesk.api.swagger-ui.enabled=true}, the Swagger UI and its static resources need
 * no token, as the UI itself obtains the token
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = {"classpath:application.properties"},
        properties = {"security.oauth2.resource.user-info-uri = http://localhost:8090",
                "spring.profiles.active=auth",
                "tesk.api.swagger-ui.enabled=true"})
public class SwaggerUiEnabledAuthIT {

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
    public void uiAndStaticResourcesWithoutToken_areOk() throws Exception {
        for (String path : UI_AND_STATIC_RESOURCES) {
            this.mvc.perform(get(path))
                    .andExpect(status().isOk());
        }
    }
}
