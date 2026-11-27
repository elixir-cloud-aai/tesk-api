package uk.ac.ebi.tsc.tesk.config.swagger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.tsc.tesk.TestAuth;

/**
 * The Swagger UI is a public client: its configuration and the API description never contain the
 * client secret, also if one is configured (the setting is deprecated), for both grant types.
 */
@SpringBootTest(
        properties = {
            "tesk.api.swagger-ui.enabled=true",
            "tesk.api.swagger-oauth.implicit=false",
            "tesk.api.swagger-oauth.client-id=swagger-client-id",
            "tesk.api.swagger-oauth.client-secret=" + SwaggerOAuthClientSecretTest.SECRET,
            TestAuth.ISSUER_URL,
            TestAuth.AUDIENCES,
            TestAuth.DECISION_SERVICE
        })
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("auth")
@AutoConfigureMockMvc
public class SwaggerOAuthClientSecretTest {

    static final String SECRET = "configured-client-secret-value";

    @Autowired private MockMvc mvc;

    @Autowired private OAuth2ClientProperties properties;

    /** The startup check logs the warning, without the secret. */
    @Test
    public void startupCheck_logsWarningWithoutSecret(CapturedOutput output) {
        this.properties.warnAboutClientSecret();
        assertThat(output.getOut(), containsString("TESK_API_SWAGGER_OAUTH_CLIENT_SECRET"));
        assertThat(output.getOut(), not(containsString(SECRET)));
    }

    /** The Swagger UI gets the client ID (in its initializer), but not the secret. */
    @Test
    public void securityConfiguration_hasNoClientSecret() throws Exception {
        this.mvc
                .perform(get("/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"clientId\":\"swagger-client-id\"")))
                .andExpect(content().string(not(containsString("clientSecret"))))
                .andExpect(content().string(not(containsString(SECRET))));
        this.mvc
                .perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(content().string(not(containsString(SECRET))));
    }

    /** Regression guard: the API description doesn't contain the secret either. */
    @Test
    public void apiDescription_hasNoClientSecret() throws Exception {
        this.mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(SECRET))));
    }
}
