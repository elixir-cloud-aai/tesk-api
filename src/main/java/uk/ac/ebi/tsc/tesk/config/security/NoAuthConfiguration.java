package uk.ac.ebi.tsc.tesk.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

/**
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 * <p>
 * Switches off spring security for all requests
 * making all requests anonymous
 */

@Configuration
@Profile("noauth")
public class NoAuthConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.
                anonymous().and().
                authorizeRequests().anyRequest().permitAll()
                .and().build();
    }

    /**
     * Switches off default in memory user store containing one user with auto-generated password
     *
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> null;
    }
}
