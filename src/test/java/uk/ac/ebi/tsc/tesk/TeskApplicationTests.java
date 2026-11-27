package uk.ac.ebi.tsc.tesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import uk.ac.ebi.tsc.tesk.config.security.token.JwtAccessTokenValidator;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenValidator;

/** With the default settings plus an issuer, TESK API starts with authentication (#227) */
@SpringBootTest(properties = {TestAuth.ISSUER_URL, TestAuth.AUDIENCES})
public class TeskApplicationTests {

    @Autowired private Environment environment;

    @Autowired private TokenValidator tokenValidator;

    @Test
    public void contextLoads_withAuthByDefault() {
        assertThat(this.environment.getActiveProfiles()).containsExactly("auth");
        assertThat(this.tokenValidator).isInstanceOf(JwtAccessTokenValidator.class);
    }
}
