package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SERVICE_INFO_URL;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration testing of GET /service-info with authentication on and the setting that requires a
 * token for it. OIDC userInfo endpoint is WireMocked
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            TestIssuer.ISSUER_URL,
            TestIssuer.AUDIENCES,
            "spring.profiles.active=auth",
            "tesk.api.service-info.public=false"
        })
public class ServiceInfoTokenRequiredIT {

    @Autowired private MockMvc mvc;

    @RegisterExtension
    public WireMockExtension mockIssuer =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(TestIssuer.PORT))
                    .failOnUnmatchedRequests(true)
                    .build();

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
        this.mvc.perform(get(SERVICE_INFO_URL)).andExpect(status().isUnauthorized());
        this.mvc.perform(head(SERVICE_INFO_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    public void withValidToken_isOk() throws Exception {
        TestIssuer.stub(mockIssuer);
        this.mvc
                .perform(
                        get(SERVICE_INFO_URL)
                                .header("Authorization", "Bearer " + TestIssuer.userToken("123")))
                .andExpect(status().isOk());
    }

    /** With service info private, its token is validated like any other */
    @Test
    public void withInvalidToken_isUnauthorized() throws Exception {
        this.mvc
                .perform(get(SERVICE_INFO_URL).header("Authorization", "Bearer BAR"))
                .andExpect(status().isUnauthorized());
    }
}
