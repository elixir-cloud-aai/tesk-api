package uk.ac.ebi.tsc.tesk.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/** The opt-in CORS settings: off without origins, and only exact origins when on */
public class CorsConfigTest {

    private static CorsConfiguration configFor(CorsConfigurationSource source, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Origin", "https://client.example.org");
        return source.getCorsConfiguration(request);
    }

    private static boolean enabled(String value) {
        MockEnvironment environment = new MockEnvironment();
        if (value != null) {
            environment.setProperty(CorsConfig.PROPERTY, value);
        }
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return new CorsConfig.Enabled().matches(context, null);
    }

    @Test
    public void noOrigins_isDisabled() {
        assertFalse(enabled(null));
        assertFalse(enabled(""));
        assertFalse(enabled(" , "));
    }

    @Test
    public void origins_isEnabled() {
        assertTrue(enabled("https://client.example.org"));
        assertTrue(enabled(" , https://client.example.org"));
    }

    @Test
    public void condition_rejectsInvalidOrigin() {
        assertThrows(IllegalArgumentException.class, () -> enabled("*"));
    }

    @Test
    public void blankOrigins_areIgnored() {
        assertEquals(
                List.of("https://client.example.org"),
                CorsConfig.origins(List.of(" ", "", " https://client.example.org ")));
        assertEquals(Collections.emptyList(), CorsConfig.origins(Collections.emptyList()));
    }

    @Test
    public void origins_allowedOnAllPaths() {
        CorsConfigurationSource source =
                new CorsConfig(List.of("https://client.example.org", " http://localhost:3000 "))
                        .corsConfigurationSource();

        CorsConfiguration config = configFor(source, "/ga4gh/tes/v1/tasks/task-1:cancel");

        assertEquals(
                List.of("https://client.example.org", "http://localhost:3000"),
                config.getAllowedOrigins());
        assertNull(config.getAllowedOriginPatterns());
        assertEquals(List.of("GET", "HEAD", "POST"), config.getAllowedMethods());
        assertEquals(List.of("Authorization", "Content-Type"), config.getAllowedHeaders());
        assertEquals(List.of("WWW-Authenticate"), config.getExposedHeaders());
        assertFalse(config.getAllowCredentials());
        assertEquals(1800L, config.getMaxAge());
        assertNotNull(configFor(source, "/v3/api-docs"));
    }

    @Test
    public void origin_withTrailingSlash_matchesBrowserOrigin() {
        CorsConfiguration config =
                configFor(
                        new CorsConfig(List.of("https://client.example.org/"))
                                .corsConfigurationSource(),
                        "/ga4gh/tes/v1/tasks");

        assertEquals(
                "https://client.example.org", config.checkOrigin("https://client.example.org"));
        assertNull(config.checkOrigin("https://other.example.org"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "*",
                "https://*.example.org",
                "null",
                "client.example.org",
                "ftp://client.example.org",
                "https://client.example.org/path",
                "https://client.example.org?query",
                "https://client.example.org#fragment",
                "https://user@client.example.org",
                "https:///nohost",
                "https://client example.org"
            })
    public void invalidOrigin_isRejected(String origin) {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> new CorsConfig(List.of(origin)));

        assertTrue(exception.getMessage().contains("TESK_API_CORS_ALLOWED_ORIGINS"));
    }

    @Test
    public void environmentVariable_bindsCommaSeparatedList() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                "env",
                                Collections.singletonMap(
                                        "TESK_API_CORS_ALLOWED_ORIGINS",
                                        "https://a.example.org,https://b.example.org")));
        ConfigurationPropertySources.attach(environment);

        assertEquals(
                List.of("https://a.example.org", "https://b.example.org"),
                Binder.get(environment)
                        .bind(CorsConfig.PROPERTY, Bindable.listOf(String.class))
                        .get());
    }
}
