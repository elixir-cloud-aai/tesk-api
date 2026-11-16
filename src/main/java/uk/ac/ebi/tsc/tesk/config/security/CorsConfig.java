package uk.ac.ebi.tsc.tesk.config.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cross-origin requests (CORS) of browser clients. Off by default: without allowed origins ({@value
 * #PROPERTY}), there is no {@link #corsConfigurationSource()} bean, so the security filter chains
 * have no CORS filter and answers carry no CORS headers. With allowed origins, Spring Security adds
 * the CORS filter with this bean to the security filter chains of both profiles ({@link
 * ResourceServerConfiguration}, {@link NoAuthConfiguration}; it does so for every chain when a bean
 * of type {@link UrlBasedCorsConfigurationSource} exists), before authentication, so that preflight
 * requests need no token. Only the allowed origins, given exactly (scheme, host and port, no
 * wildcards), may then call the API from a browser, with the methods and headers that the TES API
 * uses, and without credentials (cookies), as clients send tokens in the {@code Authorization}
 * header. Requests with an {@code Origin} header of another origin are refused (403). Invalid
 * origins stop the app at startup.
 */
@Configuration
public class CorsConfig {

    static final String PROPERTY = "tesk.api.cors.allowed-origins";
    private static final String ENV = "TESK_API_CORS_ALLOWED_ORIGINS";

    private final List<String> allowedOrigins;

    /**
     * Reads {@value #PROPERTY} as {@link Enabled} does, so that both see the same origins
     *
     * @throws IllegalArgumentException for an invalid origin (see {@link #origins(List)})
     */
    @Autowired
    public CorsConfig(Environment environment) {
        this(configured(environment));
    }

    /**
     * @param allowedOrigins the origins of {@value #PROPERTY}
     * @throws IllegalArgumentException for an invalid origin (see {@link #origins(List)})
     */
    CorsConfig(List<String> allowedOrigins) {
        this.allowedOrigins = origins(allowedOrigins);
    }

    /**
     * The entries of {@value #PROPERTY} in any form that Spring Boot binds to a list (e.g. comma
     * separated, or indexed), none if unset
     */
    static List<String> configured(Environment environment) {
        return Binder.get(environment)
                .bind(PROPERTY, Bindable.listOf(String.class))
                .orElse(List.of());
    }

    /**
     * The allowed origins, trimmed, without blank entries
     *
     * @throws IllegalArgumentException for an origin that isn't an exact {@code http} or {@code
     *     https} origin, so that the app fails to start
     */
    static List<String> origins(List<String> allowedOrigins) {
        List<String> origins = new ArrayList<>();
        for (String origin : allowedOrigins) {
            String trimmed = origin.trim();
            if (!trimmed.isEmpty()) {
                origins.add(validated(trimmed));
            }
        }
        return List.copyOf(origins);
    }

    /** An exact origin (scheme {@code http} or {@code https}, host, optional port), as given */
    private static String validated(String origin) {
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException e) {
            throw invalid(origin);
        }
        String path = uri.getRawPath();
        boolean valid =
                ("http".equalsIgnoreCase(uri.getScheme())
                                || "https".equalsIgnoreCase(uri.getScheme()))
                        // null also for hosts that aren't valid host names, e.g. with wildcards
                        && uri.getHost() != null
                        && uri.getRawUserInfo() == null
                        && (path.isEmpty() || "/".equals(path))
                        && uri.getRawQuery() == null
                        && uri.getRawFragment() == null
                        // browsers leave out default ports, so these would never match
                        && !uri.getRawAuthority().endsWith(":")
                        && uri.getPort() != defaultPort(uri.getScheme());
        if (!valid) {
            throw invalid(origin);
        }
        return origin;
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static IllegalArgumentException invalid(String origin) {
        return new IllegalArgumentException(
                "Invalid origin '"
                        + origin
                        + "' in "
                        + PROPERTY
                        + " ("
                        + ENV
                        + "): use exact origins such as https://client.example.org (scheme http or"
                        + " https, host, optional port), without wildcards, paths, user info or default ports");
    }

    /**
     * The CORS settings of all paths, only with allowed origins. Of type {@link
     * UrlBasedCorsConfigurationSource}, the type by which Spring Security finds it for the filter
     * chains
     */
    @Bean
    @Conditional(Enabled.class)
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "HEAD", "POST"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("WWW-Authenticate"));
        config.setAllowCredentials(false);
        config.setMaxAge(1800L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** Matches if {@value #PROPERTY} lists at least one origin (and all its origins are valid) */
    static class Enabled implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !origins(configured(context.getEnvironment())).isEmpty();
        }
    }
}
