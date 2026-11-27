package uk.ac.ebi.tsc.tesk.config.security;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Switches off spring security for all requests making all requests anonymous. Only by explicit
 * choice (profile {@code noauth}; the default is {@code auth}), and named in a warning at startup:
 * every caller is then the anonymous user, who owns, gets, lists and cancels all tasks created
 * without authentication.
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@Configuration
@Profile("noauth")
public class NoAuthConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(NoAuthConfiguration.class);

    /** The warning at startup */
    static final String WARNING =
            "Authentication is off (profile noauth): every caller is anonymous and may create,"
                    + " get, list and cancel all tasks created without authentication; use the"
                    + " profile auth (the default) for any installation that others can reach";

    /** The settings of the decision service, which needs authentication */
    static final List<String> AUTHZ = List.of("tesk.api.authz");

    /**
     * @throws IllegalStateException if a setting of the decision service ({@code tesk.api.authz.*})
     *     is set: without authentication, no caller has an identity that it could decide about, and
     *     every caller may create tasks, which the setting suggests is not intended
     */
    public NoAuthConfiguration(Environment environment) {
        Optional<ConfigurationPropertyName> authz =
                PropertyNames.firstSet(
                        environment, name -> PropertyNames.below(name, AUTHZ).isPresent());
        if (authz.isPresent()) {
            throw new IllegalStateException(
                    authz.get()
                            + ": the decision service needs authentication (profile auth); remove"
                            + " the tesk.api.authz settings with the profile noauth");
        }
        logger.warn(WARNING);
    }

    /**
     * Lets all requests in as anonymous, without Spring Security's other defaults (CSRF protection,
     * security headers, sessions, request cache, logout), as before with {@code
     * WebSecurityConfigurerAdapter(disableDefaults = true)}; with allowed origins of CORS, Spring
     * Security adds the CORS filter of {@link CorsConfig}
     */
    @Bean
    public SecurityFilterChain noAuthFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .headers(headers -> headers.disable())
                .sessionManagement(session -> session.disable())
                .requestCache(requestCache -> requestCache.disable())
                .logout(logout -> logout.disable())
                .anonymous(Customizer.withDefaults())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Switches off default in memory user store containing one user with auto-generated password
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> null;
    }
}
