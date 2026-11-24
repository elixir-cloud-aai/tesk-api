package uk.ac.ebi.tsc.tesk.config.security.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.jose4j.jwt.consumer.ErrorCodeValidator;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Selection of the validator, the binding of the settings, and the principal */
public class TokenValidatorsTest {

    private static TokenValidationProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("tesk.api.auth", Bindable.of(TokenValidationProperties.class));
    }

    @Test
    public void settings_areBound_fromProperties() {
        TokenValidationProperties properties =
                bind(
                        Map.ofEntries(
                                Map.entry("tesk.api.auth.token-validator", "jwt"),
                                Map.entry("tesk.api.auth.algorithms", "RS256,PS256"),
                                Map.entry("tesk.api.auth.require-access-token-type", "false"),
                                Map.entry("tesk.api.auth.clock-skew-seconds", "5"),
                                Map.entry("tesk.api.auth.max-token-age-seconds", "600"),
                                Map.entry("tesk.api.auth.timeout-seconds", "3"),
                                Map.entry(
                                        "tesk.api.auth.issuers[0].url",
                                        "https://login.aai.lifescience-ri.eu/oidc/"),
                                Map.entry(
                                        "tesk.api.auth.issuers[0].jwks-uri",
                                        "https://login.aai.lifescience-ri.eu/oidc/jwk"),
                                Map.entry("tesk.api.auth.issuers[0].audiences", "portal,cli"),
                                Map.entry("tesk.api.auth.issuers[0].client-claim", "azp"),
                                Map.entry(
                                        "tesk.api.auth.issuers[0].service-clients[0].client-id",
                                        "protes")));

        assertEquals(TokenValidationProperties.Validator.JWT, properties.getTokenValidator());
        assertEquals(List.of("RS256", "PS256"), properties.getAlgorithms());
        assertEquals(false, properties.isRequireAccessTokenType());
        assertEquals(5, properties.getClockSkewSeconds());
        assertEquals(600, properties.getMaxTokenAgeSeconds());
        assertEquals(3, properties.getTimeoutSeconds());
        TokenValidationProperties.Issuer issuer = properties.getIssuers().get(0);
        assertEquals("https://login.aai.lifescience-ri.eu/oidc/", issuer.getUrl());
        assertEquals("https://login.aai.lifescience-ri.eu/oidc/jwk", issuer.getJwksUri());
        assertEquals(List.of("portal", "cli"), issuer.getAudiences());
        assertEquals("azp", issuer.getClientClaim());
        assertEquals("protes", issuer.getServiceClients().get(0).getClientId());
        assertInstanceOf(JwtAccessTokenValidator.class, TokenValidators.create(properties));
    }

    @Test
    public void defaults_areTheSecureOnes() {
        TokenValidationProperties properties = bind(Map.of());

        assertEquals(TokenValidationProperties.Validator.JWT, properties.getTokenValidator());
        assertEquals(List.of("RS256", "ES256"), properties.getAlgorithms());
        assertTrue(properties.isRequireAccessTokenType());
        assertEquals(60, properties.getClockSkewSeconds());
        assertEquals(86_400, properties.getMaxTokenAgeSeconds());
        assertEquals(10, properties.getTimeoutSeconds());
        assertTrue(properties.getIssuers().isEmpty());
        assertEquals("client_id", new TokenValidationProperties.Issuer().getClientClaim());
    }

    @Test
    public void defaults_failAtStartup_withoutIssuers() {
        TokenValidationProperties properties = bind(Map.of());

        assertThrows(IllegalStateException.class, () -> TokenValidators.create(properties));
    }

    @Test
    public void unknownValidator_failsBinding() {
        assertThrows(
                RuntimeException.class,
                () -> bind(Map.of("tesk.api.auth.token-validator", "introspection")));
    }

    @Test
    public void noValidator_failsAtStartup() {
        TokenValidationProperties properties = new TokenValidationProperties();
        properties.setTokenValidator(null);

        assertThrows(NullPointerException.class, () -> TokenValidators.create(properties));
    }

    @Test
    public void owner_isIssuerAndSubject() {
        TokenPrincipal principal =
                new TokenPrincipal(
                        "https://login.example.org/",
                        "user-1",
                        TokenPrincipal.Kind.USER,
                        null,
                        Map.of("sub", "user-1"));

        assertEquals("https://login.example.org/|user-1", principal.owner());
        assertThrows(UnsupportedOperationException.class, () -> principal.claims().put("x", "y"));
    }

    @Test
    public void reasons_withoutDetails_areGeneric() {
        InvalidJwtException e =
                new InvalidJwtException("x", List.<ErrorCodeValidator.Error>of(), null);

        assertEquals("invalid token", JwtAccessTokenValidator.reasons(e));
    }
}
