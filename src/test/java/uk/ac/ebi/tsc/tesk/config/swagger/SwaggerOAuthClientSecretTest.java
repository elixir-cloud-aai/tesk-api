package uk.ac.ebi.tsc.tesk.config.swagger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Swagger UI is a public client: its configuration and the API description never contain the client
 * secret, also if one is configured (the setting is deprecated), for both grant types.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "tesk.api.swagger-ui.enabled=true",
        "tesk.api.swagger-oauth.implicit=false",
        "tesk.api.swagger-oauth.client-id=swagger-client-id",
        "tesk.api.swagger-oauth.client-secret=" + SwaggerOAuthClientSecretTest.SECRET})
@ActiveProfiles("auth")
@AutoConfigureMockMvc
public class SwaggerOAuthClientSecretTest {

    static final String SECRET = "configured-client-secret-value";

    @Autowired
    private MockMvc mvc;

    @Test
    public void securityConfiguration_hasNoClientSecret() throws Exception {
        this.mvc.perform(get("/swagger-resources/configuration/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("swagger-client-id"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(content().string(not(containsString(SECRET))));
    }

    /**
     * Regression guard: the API description doesn't contain the secret either.
     */
    @Test
    public void apiDescription_hasNoClientSecret() throws Exception {
        this.mvc.perform(get("/v2/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(SECRET))));
    }
}
