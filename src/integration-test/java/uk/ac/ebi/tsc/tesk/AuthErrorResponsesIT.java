package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Integration testing of the answers to requests without a valid access token (auth profile): the
 * status, the headers ({@code WWW-Authenticate}) and the JSON body. The user info endpoint is
 * WireMocked.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "security.oauth2.resource.user-info-uri = http://localhost:8090",
            "spring.profiles.active=auth"
        })
public class AuthErrorResponsesIT {

    private static final String TOKEN = "TOKEN-THAT-MUST-NOT-BE-ECHOED";

    private static final String NO_TOKEN_MESSAGE =
            "Full authentication is required to access this resource";

    private static final String INVALID_TOKEN_MESSAGE = "Invalid access token";

    @Autowired private MockMvc mvc;

    @Rule public WireMockRule mockUserInfo = new WireMockRule(8090);

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    /** The answer to a request without a usable token: as before the resource server moved */
    private static void expectUnauthorized(ResultActions result, String error, String description)
            throws Exception {
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
                .andExpect(header().string("WWW-Authenticate", not(containsString(TOKEN))))
                .andExpect(content().string(not(containsString(TOKEN))));
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
        expectUnauthorized(result, "unauthorized", NO_TOKEN_MESSAGE);
        expectBody(result, "unauthorized", NO_TOKEN_MESSAGE);
        expectUnauthorized(this.mvc.perform(head(TASK_URL)), "unauthorized", NO_TOKEN_MESSAGE);
        mockUserInfo.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    public void otherScheme_isUnauthorized() throws Exception {
        ResultActions result =
                this.mvc.perform(get(TASK_URL).header("Authorization", "Basic Zm9vOmJhcg=="));
        expectUnauthorized(result, "unauthorized", NO_TOKEN_MESSAGE);
        expectBody(result, "unauthorized", NO_TOKEN_MESSAGE);
        mockUserInfo.verify(0, anyRequestedFor(anyUrl()));
    }

    /** The user info endpoint gets the token as a bearer token, and is asked for JSON */
    @Test
    public void validToken_isSentToUserInfo() throws Exception {
        mockUserInfo.givenThat(
                WireMock.get("/")
                        .willReturn(okJson("{\"sub\":\"123\",\"eduperson_entitlement\":[]}")));
        this.mvc
                .perform(get(TASK_URL).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
        mockUserInfo.verify(
                1,
                getRequestedFor(urlEqualTo("/"))
                        .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                        .withHeader("Accept", containing("application/json")));
    }

    private void expectInvalidToken() throws Exception {
        ResultActions result =
                this.mvc.perform(get(TASK_URL).header("Authorization", "Bearer " + TOKEN));
        expectUnauthorized(result, "invalid_token", INVALID_TOKEN_MESSAGE);
        expectBody(result, "invalid_token", INVALID_TOKEN_MESSAGE);
    }

    @Test
    public void tokenRefusedByUserInfo_isUnauthorized() throws Exception {
        mockUserInfo.givenThat(WireMock.get("/").willReturn(unauthorized()));
        expectInvalidToken();
    }

    @Test
    public void userInfoFails_isUnauthorized() throws Exception {
        mockUserInfo.givenThat(WireMock.get("/").willReturn(serverError()));
        expectInvalidToken();
    }

    @Test
    public void userInfoWithError_isUnauthorized() throws Exception {
        mockUserInfo.givenThat(
                WireMock.get("/").willReturn(okJson("{\"error\":\"invalid_token\"}")));
        expectInvalidToken();
    }

    @Test
    public void userInfoWithoutSub_isUnauthorized() throws Exception {
        mockUserInfo.givenThat(WireMock.get("/").willReturn(okJson("{\"name\":\"x\"}")));
        expectInvalidToken();
    }

    @Test
    public void userInfoNotJson_isUnauthorized() throws Exception {
        mockUserInfo.givenThat(
                WireMock.get("/")
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("[\"sub\"]")));
        expectInvalidToken();
    }

    /** Malformed bearer tokens are refused without asking the user info endpoint */
    @Test
    public void malformedToken_isUnauthorized() throws Exception {
        mockUserInfo.givenThat(
                WireMock.get("/")
                        .willReturn(okJson("{\"sub\":\"123\",\"eduperson_entitlement\":[]}")));
        for (String value : new String[] {"Bearer ", "Bearer a b", "Bearer a\"b"}) {
            this.mvc
                    .perform(get(TASK_URL).header("Authorization", value))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_token"));
        }
        mockUserInfo.verify(0, anyRequestedFor(anyUrl()));
    }

    /** Tokens are only taken from the Authorization header, not from the URL */
    @Test
    public void tokenInQuery_isIgnored() throws Exception {
        mockUserInfo.givenThat(
                WireMock.get("/")
                        .willReturn(okJson("{\"sub\":\"123\",\"eduperson_entitlement\":[]}")));
        ResultActions result = this.mvc.perform(get(TASK_URL).param("access_token", TOKEN));
        expectUnauthorized(result, "unauthorized", NO_TOKEN_MESSAGE);
        mockUserInfo.verify(0, anyRequestedFor(anyUrl()));
    }
}
