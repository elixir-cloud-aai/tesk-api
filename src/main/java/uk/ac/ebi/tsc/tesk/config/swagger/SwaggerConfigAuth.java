package uk.ac.ebi.tsc.tesk.config.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Swagger Configuration Configures securing all endpoints with OAuth2 (swaggerUI acts as OAuh2
 * client): the API description (springdoc) names the OAuth 2.0 flow of the Swagger UI, with the
 * endpoints and scopes of {@link OAuth2ClientProperties}; the Swagger UI gets its client ID from
 * {@code springdoc.swagger-ui.oauth.client-id} ({@code application.properties}).
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
@Configuration
@Profile("auth")
public class SwaggerConfigAuth {

    /** Name of the security scheme in the API description */
    static final String SECURITY_SCHEME = "oauth2";

    private final OAuth2ClientProperties properties;

    public SwaggerConfigAuth(OAuth2ClientProperties properties) {
        this.properties = properties;
    }

    /** The API description's security scheme, which all endpoints use */
    @Bean
    public OpenAPI api() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME, oauth()))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }

    /**
     * The flow of the Swagger UI: implicit, or authorization code (also if unset). The Swagger UI
     * runs in the browser, so it is a public client: it gets no client secret.
     */
    SecurityScheme oauth() {
        Scopes scopes = new Scopes();
        properties.getScopesAsMap().forEach(scopes::addString);
        OAuthFlows flows = new OAuthFlows();
        if (Boolean.TRUE.equals(properties.getImplicit())) {
            flows.implicit(
                    new OAuthFlow()
                            .authorizationUrl(properties.getAuthorizationEndpoint())
                            .scopes(scopes));
        } else {
            flows.authorizationCode(
                    new OAuthFlow()
                            .authorizationUrl(properties.getAuthorizationEndpoint())
                            .tokenUrl(properties.getTokenEndpoint())
                            .scopes(scopes));
        }
        return new SecurityScheme().type(SecurityScheme.Type.OAUTH2).flows(flows);
    }
}
