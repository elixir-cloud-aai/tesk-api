package uk.ac.ebi.tsc.tesk.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Switches off spring security for all requests making all requests anonymous
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@Configuration
@Profile("noauth")
public class NoAuthConfiguration {

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
