package uk.ac.ebi.tsc.tesk;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import org.junit.Rule;
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

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SERVICE_INFO_URL;

/**
 * Integration testing of GET /service-info with authentication on and the setting that requires a token for it.
 * OIDC userInfo endpoint is WireMocked
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = {"classpath:application.properties"},
        properties = {"security.oauth2.resource.user-info-uri = http://localhost:8090",
                "spring.profiles.active=auth",
                "tesk.api.service-info.public=false"})
public class ServiceInfoTokenRequiredIT {

    @Autowired
    private MockMvc mvc;

    @Rule
    public WireMockRule mockElixir = new WireMockRule(8090);

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    @Test
    public void withoutToken_isUnauthorized() throws Exception {
        this.mvc.perform(get(SERVICE_INFO_URL))
                .andExpect(status().isUnauthorized());
        this.mvc.perform(head(SERVICE_INFO_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void withValidToken_isOk() throws Exception {
        mockElixir.givenThat(WireMock.get("/")
                .willReturn(okJson("{\"sub\" : \"123\",  \"eduperson_entitlement\" : []}")));

        this.mvc.perform(get(SERVICE_INFO_URL)
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isOk());
    }

    @Test
    public void withInvalidToken_isUnauthorized() throws Exception {
        mockElixir.givenThat(WireMock.get("/").willReturn(unauthorized()));

        this.mvc.perform(get(SERVICE_INFO_URL)
                .header("Authorization", "Bearer BAR"))
                .andExpect(status().isUnauthorized());
    }
}
