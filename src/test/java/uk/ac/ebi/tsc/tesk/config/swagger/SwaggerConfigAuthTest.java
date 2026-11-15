package uk.ac.ebi.tsc.tesk.config.swagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** The OAuth 2.0 flow of the Swagger UI, and its scopes */
public class SwaggerConfigAuthTest {

    private static OAuth2ClientProperties properties(Boolean implicit) {
        OAuth2ClientProperties properties = new OAuth2ClientProperties();
        properties.setAuthorizationEndpoint("https://login.example.org/authorize");
        properties.setTokenEndpoint("https://login.example.org/token");
        properties.setImplicit(implicit);
        properties.setScopes(Arrays.asList("openid:OpenID Connect", "email:E-mail address"));
        return properties;
    }

    private static OAuthFlows flows(Boolean implicit) {
        OpenAPI api = new SwaggerConfigAuth(properties(implicit)).api();
        assertThat(api.getSecurity())
                .singleElement()
                .satisfies(
                        requirement ->
                                assertThat(requirement)
                                        .containsOnlyKeys(SwaggerConfigAuth.SECURITY_SCHEME));
        SecurityScheme scheme =
                api.getComponents().getSecuritySchemes().get(SwaggerConfigAuth.SECURITY_SCHEME);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.OAUTH2);
        return scheme.getFlows();
    }

    /** With the implicit flow, the Swagger UI gets the token from the authorization endpoint */
    @Test
    public void implicitGrant() {
        OAuthFlows flows = flows(true);

        assertThat(flows.getAuthorizationCode()).isNull();
        OAuthFlow flow = flows.getImplicit();
        assertThat(flow.getAuthorizationUrl()).isEqualTo("https://login.example.org/authorize");
        assertThat(flow.getTokenUrl()).isNull();
        assertThat(flow.getScopes())
                .containsExactly(
                        entry("openid", "OpenID Connect"), entry("email", "E-mail address"));
    }

    /** Otherwise (also if unset), it uses the authorization code flow */
    @Test
    public void authorizationCodeGrant() {
        for (Boolean implicit : Arrays.asList(false, null)) {
            OAuthFlows flows = flows(implicit);

            assertThat(flows.getImplicit()).isNull();
            OAuthFlow flow = flows.getAuthorizationCode();
            assertThat(flow.getTokenUrl()).isEqualTo("https://login.example.org/token");
            assertThat(flow.getAuthorizationUrl()).isEqualTo("https://login.example.org/authorize");
            assertThat(flow.getScopes()).containsOnlyKeys("openid", "email");
        }
    }

    /** The Swagger UI's client ID (springdoc.swagger-ui.oauth.client-id) */
    @Test
    public void clientId() {
        OAuth2ClientProperties properties = new OAuth2ClientProperties();
        properties.setClientId("swagger-ui");
        assertThat(properties.getClientId()).isEqualTo("swagger-ui");
    }

    /** Scopes are listed as name:description; without them, there are none */
    @Test
    public void scopes() {
        OAuth2ClientProperties properties = new OAuth2ClientProperties();
        assertThat(properties.getScopesAsMap()).isEmpty();
        properties.setScopes(Arrays.asList("openid:OpenID Connect", "email:E-mail address"));
        assertThat(properties.getScopesAsMap())
                .containsExactly(
                        entry("openid", "OpenID Connect"), entry("email", "E-mail address"));
        properties.setScopes(Collections.emptyList());
        assertThat(properties.getScopesAsMap()).isEmpty();
    }
}
