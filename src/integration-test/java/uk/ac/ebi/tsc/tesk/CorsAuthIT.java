package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SERVICE_INFO_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import com.github.tomakehurst.wiremock.client.WireMock;
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
 * CORS with authentication on and allowed origins configured: preflight requests of the allowed
 * origins are answered without a token (the user info endpoint, WireMocked without stubs, is never
 * asked), those of other origins are refused, and the answers to the actual requests of the allowed
 * origins carry the CORS headers, also when they are refused for a missing token
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            TestIssuer.ISSUER_URL,
            TestIssuer.AUDIENCES,
            TestIssuer.DECISION_SERVICE,
            "spring.profiles.active=auth",
            "tesk.api.cors.allowed-origins=https://client.example.org,http://localhost:3000"
        })
public class CorsAuthIT {

    static final String ALLOWED_ORIGIN = "https://client.example.org";
    static final String OTHER_ORIGIN = "https://other.example.org";

    @Autowired private MockMvc mvc;

    @RegisterExtension
    public WireMockExtension mockIssuer =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(TestIssuer.PORT))
                    .failOnUnmatchedRequests(true)
                    .build();

    @RegisterExtension
    public WireMockExtension mockKubernetes =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(9000))
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
    public void preflight_allowedOrigin_isOkWithoutToken() throws Exception {
        this.mvc
                .perform(
                        options(TASK_URL)
                                .header("Origin", ALLOWED_ORIGIN)
                                .header("Access-Control-Request-Method", "POST")
                                .header(
                                        "Access-Control-Request-Headers",
                                        "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,HEAD,POST"))
                .andExpect(
                        header().string(
                                        "Access-Control-Allow-Headers",
                                        "authorization, content-type"))
                .andExpect(header().string("Access-Control-Max-Age", "1800"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    public void preflight_secondAllowedOrigin_isOk() throws Exception {
        this.mvc
                .perform(
                        options(TASK_URL + "/task-1:cancel")
                                .header("Origin", "http://localhost:3000")
                                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    public void preflight_otherOrigin_isForbidden() throws Exception {
        this.mvc
                .perform(
                        options(TASK_URL)
                                .header("Origin", OTHER_ORIGIN)
                                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    public void preflight_otherMethod_isForbidden() throws Exception {
        this.mvc
                .perform(
                        options(TASK_URL)
                                .header("Origin", ALLOWED_ORIGIN)
                                .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    public void preflight_otherHeader_isForbidden() throws Exception {
        this.mvc
                .perform(
                        options(TASK_URL)
                                .header("Origin", ALLOWED_ORIGIN)
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "x-custom"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    public void request_allowedOriginWithoutToken_isUnauthorizedWithCorsHeaders() throws Exception {
        this.mvc
                .perform(get(TASK_URL).header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", "WWW-Authenticate"))
                .andExpect(header().exists("WWW-Authenticate"));
    }

    @Test
    public void request_otherOrigin_isForbidden() throws Exception {
        this.mvc
                .perform(get(TASK_URL).header("Origin", OTHER_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    public void request_withoutOrigin_isUnauthorizedWithoutCorsHeaders() throws Exception {
        this.mvc
                .perform(get(TASK_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /** Public service info ignores the token, so the issuer isn't asked */
    @Test
    public void request_allowedOriginWithToken_isOkWithCorsHeaders() throws Exception {
        this.mvc
                .perform(
                        get(SERVICE_INFO_URL)
                                .header("Origin", ALLOWED_ORIGIN)
                                .header("Authorization", "Bearer " + TestIssuer.userToken("123")))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
        mockIssuer.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    public void request_allowedOriginWithValidToken_isOkWithCorsHeaders() throws Exception {
        TestIssuer.stub(mockIssuer);
        mockKubernetes.givenThat(
                WireMock.get(WireMock.urlPathEqualTo("/apis/batch/v1/namespaces/default/jobs"))
                        .willReturn(okJson(MockUtil.jobList())));
        this.mvc
                .perform(
                        get(TASK_URL)
                                .header("Origin", ALLOWED_ORIGIN)
                                .header("Authorization", "Bearer " + TestIssuer.userToken("123")))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    public void request_allowedOriginOtherMethod_isForbidden() throws Exception {
        this.mvc
                .perform(delete(TASK_URL).header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
