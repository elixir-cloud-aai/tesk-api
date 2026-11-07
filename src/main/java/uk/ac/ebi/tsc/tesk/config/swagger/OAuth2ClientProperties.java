package uk.ac.ebi.tsc.tesk.config.swagger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Properties for SwaggerUI acting as OAuth2 client
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
@Configuration
@ConfigurationProperties(prefix = "tesk.api.swagger-oauth")
public class OAuth2ClientProperties {

    private static final Logger logger = LoggerFactory.getLogger(OAuth2ClientProperties.class);

    /** Authorisation server authorisation endpoint */
    private String authorizationEndpoint;

    /** Authorisation server token endpoint */
    private String tokenEndpoint;
    /** OAuth2 Client ID */
    private String clientId;

    /** OAuth2 Client Secret; deprecated and ignored: the Swagger UI is a public client */
    @Deprecated private String clientSecret;

    /** List of Scopes, a client will request (a list of entries: scope name: description) */
    private List<String> scopes;
    /** Whether implicit (true) or auth code flow (false) */
    private Boolean implicit;

    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    public void setAuthorizationEndpoint(String authorizationEndpoint) {
        this.authorizationEndpoint = authorizationEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @Deprecated
    @DeprecatedConfigurationProperty(
            reason = "The Swagger UI is a public client and gets no client secret")
    public String getClientSecret() {
        return clientSecret;
    }

    @Deprecated
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public Map<String, String> getScopesAsMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (scopes == null) return map;
        for (String scope : scopes) {
            String[] pair = StringUtils.split(scope, ":");
            map.put(pair[0], pair[1]);
        }
        return map;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public Boolean getImplicit() {
        return implicit;
    }

    public void setImplicit(Boolean implicit) {
        this.implicit = implicit;
    }

    /** Warns about the deprecated client secret setting, which is ignored, with any profile. */
    @PostConstruct
    @SuppressWarnings("deprecation")
    public void warnAboutClientSecret() {
        deprecatedClientSecretWarning(this).ifPresent(logger::warn);
    }

    /** The warning about the deprecated client secret setting, if it is set; without the secret. */
    @SuppressWarnings("deprecation")
    static Optional<String> deprecatedClientSecretWarning(OAuth2ClientProperties properties) {
        if (properties.getClientSecret() == null || properties.getClientSecret().trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "TESK_API_SWAGGER_OAUTH_CLIENT_SECRET (tesk.api.swagger-oauth.client-secret) is deprecated "
                        + "and ignored: the Swagger UI is a public client. Remove the setting, and rotate the secret at your "
                        + "identity provider if the Swagger UI was on.");
    }
}
