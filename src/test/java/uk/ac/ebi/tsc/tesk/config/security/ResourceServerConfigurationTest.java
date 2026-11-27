package uk.ac.ebi.tsc.tesk.config.security;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import uk.ac.ebi.tsc.tesk.config.security.token.JwtAccessTokenValidator;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenValidationProperties;

/** The settings of the auth profile, and where tokens are taken from */
public class ResourceServerConfigurationTest {

    private static final String SERVICE_INFO = "/ga4gh/tes/v1/service-info";

    private static ResourceServerConfiguration configuration(boolean serviceInfoPublic) {
        return new ResourceServerConfiguration(null, null, "/ga4gh/tes/v1", serviceInfoPublic);
    }

    private static StandardEnvironment properties(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    private static StandardEnvironment environmentVariables(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                "test-"
                                        + StandardEnvironment
                                                .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                variables));
        return environment;
    }

    private static TokenValidationProperties validSettings() {
        TokenValidationProperties properties = new TokenValidationProperties();
        TokenValidationProperties.Issuer issuer = new TokenValidationProperties.Issuer();
        issuer.setUrl("https://issuer.example/");
        issuer.setAudiences(java.util.List.of("portal"));
        properties.setIssuers(java.util.List.of(issuer));
        return properties;
    }

    private static MockHttpServletRequest request(String method, String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    public void serviceInfo_isPublicByDefaultSetting() {
        String[] publicUrls = configuration(true).allowedUrls();
        assertEquals(SERVICE_INFO, publicUrls[publicUrls.length - 1]);
        String[] privateUrls = configuration(false).allowedUrls();
        assertArrayEquals(Arrays.copyOf(publicUrls, publicUrls.length - 1), privateUrls);
    }

    /** On public paths, a token (valid or not, also a malformed one) is ignored */
    @Test
    public void publicPaths_ignoreTokens() {
        BearerTokenResolver resolver =
                ResourceServerConfiguration.bearerTokenResolver(
                        ResourceServerConfiguration.publicPaths(configuration(true).allowedUrls()));
        assertNull(resolver.resolve(request("GET", SERVICE_INFO, "a b")));
        assertNull(resolver.resolve(request("HEAD", SERVICE_INFO, "x")));
        assertNull(resolver.resolve(request("GET", "/v3/api-docs", "x")));
        // other methods and paths take the token from the header
        assertEquals("x", resolver.resolve(request("POST", SERVICE_INFO, "x")));
        assertEquals("x", resolver.resolve(request("GET", SERVICE_INFO + "/", "x")));
        assertEquals("x", resolver.resolve(request("GET", "/ga4gh/tes/v1/tasks", "x")));
    }

    /** With service info private, its token is validated like any other */
    @Test
    public void privateServiceInfo_takesTheToken() {
        BearerTokenResolver resolver =
                ResourceServerConfiguration.bearerTokenResolver(
                        ResourceServerConfiguration.publicPaths(
                                configuration(false).allowedUrls()));
        assertEquals("x", resolver.resolve(request("GET", SERVICE_INFO, "x")));
    }

    /** Tokens in the URL are never taken */
    @Test
    public void tokenInQuery_isIgnored() {
        BearerTokenResolver resolver =
                ResourceServerConfiguration.bearerTokenResolver(
                        ResourceServerConfiguration.publicPaths(configuration(true).allowedUrls()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ga4gh/tes/v1/tasks");
        request.setParameter("access_token", "x");
        assertNull(resolver.resolve(request));
    }

    @Test
    public void validator_isTheConfiguredOne() {
        assertEquals(
                JwtAccessTokenValidator.class,
                configuration(true)
                        .tokenValidator(validSettings(), properties(Map.of()))
                        .getClass());
        assertEquals(
                TokenValidatorIntrospector.class,
                configuration(true)
                        .tokenIntrospector(token -> {
                            throw new IllegalStateException();
                        })
                        .getClass());
    }

    /** Without issuers (the default), TESK API doesn't start */
    @Test
    public void withoutIssuers_isRefused() {
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                configuration(true)
                                        .tokenValidator(
                                                new TokenValidationProperties(),
                                                properties(Map.of())));
        assertThat(e.getMessage(), containsString("tesk.api.auth.issuers"));
    }

    /** Service clients listed under no issuer are refused, naming where they belong */
    @Test
    public void serviceClientsWithoutIssuer_areRefused() {
        for (StandardEnvironment environment :
                new StandardEnvironment[] {
                    properties(Map.of("tesk.api.auth.service-clients[0].client-id", "gateway")),
                    properties(Map.of("tesk.api.auth.serviceClients", "gateway")),
                    properties(Map.of("tesk.api.auth.service-clients.enabled", "true")),
                    environmentVariables(
                            Map.of("TESK_API_AUTH_SERVICE_CLIENTS_0_CLIENT_ID", "gateway")),
                    environmentVariables(Map.of("TESK_API_AUTH_SERVICECLIENTS", "gateway"))
                }) {
            IllegalStateException e =
                    assertThrows(
                            IllegalStateException.class,
                            () -> configuration(true).tokenValidator(validSettings(), environment));
            assertThat(e.getMessage(), containsString("tesk.api.auth."));
            assertThat(
                    e.getMessage(),
                    containsString("tesk.api.auth.issuers[i].service-clients[j].client-id"));
        }
    }

    /** Service clients of an issuer, and similar names, are no concern */
    @Test
    public void otherSettings_areAccepted() {
        assertDoesNotThrow(() ->
                ResourceServerConfiguration.refuseMisplacedSettings(
                        properties(
                                Map.of(
                                        "tesk.api.auth.issuers[0].service-clients[0].client-id",
                                        "gateway",
                                        "tesk.api.auth.service-clientsx",
                                        "x",
                                        "tesk.api.auth.service",
                                        "x"))));
    }
}
