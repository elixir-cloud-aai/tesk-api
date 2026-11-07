package uk.ac.ebi.tsc.tesk.config.swagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import org.junit.Test;

/**
 * The deprecated client secret setting is ignored; a warning says so, without the secret, if it is
 * set.
 */
public class DeprecatedClientSecretWarningTest {

    @SuppressWarnings("deprecation")
    private static Optional<String> warningFor(String clientSecret) {
        OAuth2ClientProperties properties = new OAuth2ClientProperties();
        properties.setClientSecret(clientSecret);
        return OAuth2ClientProperties.deprecatedClientSecretWarning(properties);
    }

    @Test
    public void unset_noWarning() {
        assertEquals(Optional.empty(), warningFor(null));
        assertEquals(Optional.empty(), warningFor(""));
        assertEquals(Optional.empty(), warningFor("  "));
    }

    @Test
    public void set_warnsWithoutTheSecret() {
        Optional<String> warning = warningFor("configured-client-secret-value");
        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("TESK_API_SWAGGER_OAUTH_CLIENT_SECRET"));
        assertFalse(warning.get().contains("configured-client-secret-value"));
    }
}
