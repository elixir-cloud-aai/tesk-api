package uk.ac.ebi.tsc.tesk.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Both profiles replace Spring Boot's in-memory user store (with a generated password) by one
 * without users
 */
public class UserDetailsServiceTest {

    @Test
    public void noAuth_hasNoUsers() {
        assertThat(
                        new NoAuthConfiguration(new MockEnvironment())
                                .userDetailsService()
                                .loadUserByUsername("user"))
                .isNull();
    }

    @Test
    public void auth_hasNoUsers() {
        ResourceServerConfiguration configuration =
                new ResourceServerConfiguration(null, null, "/ga4gh/tes/v1", true);
        assertThat(configuration.userDetailsService().loadUserByUsername("user")).isNull();
    }
}
