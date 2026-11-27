package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Integration testing of the answers to requests without a valid access token (auth profile): the
 * status, the headers ({@code WWW-Authenticate}) and the JSON body, which never echo the token or
 * name why it was refused. The issuer (discovery, keys) is WireMocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "spring.profiles.active=auth",
            TestIssuer.ISSUER_URL,
            TestIssuer.AUDIENCES,
            TestIssuer.DECISION_SERVICE
        })
public class AuthErrorResponsesIT {

    private static final String TOKEN = "TOKEN-THAT-MUST-NOT-BE-ECHOED";

    private static final String NO_TOKEN_MESSAGE =
            "Full authentication is required to access this resource";

    private static final String INVALID_TOKEN_MESSAGE = "Invalid access token";

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

    @BeforeEach
    public void stubIssuer() {
        TestIssuer.stub(mockIssuer);
    }

    /** The answer to a request without a usable token: as before the resource server moved */
    private static void expectUnauthorized(
            ResultActions result, String error, String description, String token) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(
                        header().string(
                                        "WWW-Authenticate",
                                        "Bearer realm=\"oauth\", error=\""
                                                + error
                                                + "\", error_description=\""
                                                + description
                                                + "\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("WWW-Authenticate", not(containsString(token))))
                .andExpect(content().string(not(containsString(token))));
    }

    private static void expectBody(ResultActions result, String error, String description)
            throws Exception {
        result.andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value(error))
                .andExpect(jsonPath("$.message").value(description))
                .andExpect(jsonPath("$.error_description").value(description))
                .andExpect(jsonPath("$.path").value(TASK_URL))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    public void withoutToken_isUnauthorized() throws Exception {
        ResultActions result = this.mvc.perform(get(TASK_URL));
        expectUnauthorized(result, "unauthorized", NO_TOKEN_MESSAGE, TOKEN);
        expectBody(result, "unauthorized", NO_TOKEN_MESSAGE);
        expectUnauthorized(
                this.mvc.perform(head(TASK_URL)), "unauthorized", NO_TOKEN_MESSAGE, TOKEN);
        mockIssuer.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    public void otherScheme_isUnauthorized() throws Exception {
        ResultActions result =
                this.mvc.perform(get(TASK_URL).header("Authorization", "Basic Zm9vOmJhcg=="));
        expectUnauthorized(result, "unauthorized", NO_TOKEN_MESSAGE, TOKEN);
        expectBody(result, "unauthorized", NO_TOKEN_MESSAGE);
        mockIssuer.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * A valid token passes authentication without any request to the issuer but for its keys (no
     * user info); creating a task then needs the decision service, which doesn't answer here: 503
     */
    @Test
    public void validToken_isCheckedLocally() throws Exception {
        this.mvc
                .perform(
                        post(TASK_URL)
                                .header("Authorization", "Bearer " + TestIssuer.userToken("123"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"executors\":[{\"image\":\"alpine\","
                                                + "\"command\":[\"true\"]}]}"))
                .andExpect(status().isServiceUnavailable());
        mockIssuer.verify(0, getRequestedFor(urlPathMatching(".*userinfo.*")));
    }

    /** Not a JWT */
    @Test
    public void opaqueToken_isUnauthorized() throws Exception {
        ResultActions result =
                this.mvc.perform(get(TASK_URL).header("Authorization", "Bearer " + TOKEN));
        expectUnauthorized(result, "invalid_token", INVALID_TOKEN_MESSAGE, TOKEN);
        expectBody(result, "invalid_token", INVALID_TOKEN_MESSAGE);
    }

    /** A JWT that a check refuses: the answer names no reason, and doesn't echo the token */
    @Test
    public void refusedJwt_isUnauthorized() throws Exception {
        org.jose4j.jwt.JwtClaims claims = TestIssuer.userClaims("123");
        claims.setAudience("other-service");
        String token = TestIssuer.sign(claims);
        ResultActions result =
                this.mvc.perform(get(TASK_URL).header("Authorization", "Bearer " + token));
        expectUnauthorized(result, "invalid_token", INVALID_TOKEN_MESSAGE, token);
        expectBody(result, "invalid_token", INVALID_TOKEN_MESSAGE);
        result.andExpect(content().string(not(containsString("audience"))));
    }

    /** Malformed bearer tokens are refused without asking the issuer */
    @Test
    public void malformedToken_isUnauthorized() throws Exception {
        for (String value : new String[] {"Bearer ", "Bearer a b", "Bearer a\"b"}) {
            this.mvc
                    .perform(get(TASK_URL).header("Authorization", value))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_token"));
        }
        mockIssuer.verify(0, anyRequestedFor(anyUrl()));
    }

    /** Tokens are only taken from the Authorization header, not from the URL */
    @Test
    public void tokenInQuery_isIgnored() throws Exception {
        ResultActions result =
                this.mvc.perform(get(TASK_URL).param("access_token", TestIssuer.userToken("123")));
        expectUnauthorized(result, "unauthorized", NO_TOKEN_MESSAGE, TOKEN);
        mockIssuer.verify(0, anyRequestedFor(anyUrl()));
    }
}
