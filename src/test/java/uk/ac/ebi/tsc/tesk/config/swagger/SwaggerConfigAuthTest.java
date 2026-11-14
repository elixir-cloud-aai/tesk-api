package uk.ac.ebi.tsc.tesk.config.swagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import springfox.documentation.service.AuthorizationCodeGrant;
import springfox.documentation.service.GrantType;
import springfox.documentation.service.ImplicitGrant;

/** The OAuth 2.0 flow of the Swagger UI, and its scopes */
public class SwaggerConfigAuthTest {

    private static SwaggerConfigAuth config(OAuth2ClientProperties properties) {
        SwaggerConfigAuth config = new SwaggerConfigAuth();
        ReflectionTestUtils.setField(config, "properties", properties);
        return config;
    }

    private static OAuth2ClientProperties properties(Boolean implicit) {
        OAuth2ClientProperties properties = new OAuth2ClientProperties();
        properties.setAuthorizationEndpoint("https://login.example.org/authorize");
        properties.setTokenEndpoint("https://login.example.org/token");
        properties.setImplicit(implicit);
        return properties;
    }

    /** With the implicit flow, the Swagger UI gets the token from the authorization endpoint */
    @Test
    public void implicitGrant() {
        List<GrantType> grants = config(properties(true)).grantTypes();

        assertThat(grants).hasSize(1);
        ImplicitGrant grant = (ImplicitGrant) grants.get(0);
        assertThat(grant.getLoginEndpoint().getUrl())
                .isEqualTo("https://login.example.org/authorize");
        assertThat(grant.getTokenName()).isEqualTo("access_token");
    }

    /** Otherwise (also if unset), it uses the authorization code flow */
    @Test
    public void authorizationCodeGrant() {
        for (Boolean implicit : Arrays.asList(false, null)) {
            List<GrantType> grants = config(properties(implicit)).grantTypes();

            assertThat(grants).hasSize(1);
            AuthorizationCodeGrant grant = (AuthorizationCodeGrant) grants.get(0);
            assertThat(grant.getTokenEndpoint().getUrl())
                    .isEqualTo("https://login.example.org/token");
            assertThat(grant.getTokenRequestEndpoint().getUrl())
                    .isEqualTo("https://login.example.org/authorize");
        }
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
