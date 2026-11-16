package uk.ac.ebi.tsc.tesk.config.security;

import java.time.Duration;
import org.springframework.util.StringUtils;

/**
 * The setting {@code tesk.api.auth.oidc.timeout}: seconds (decimals allowed, more than 0, default
 * 10) that TESK API waits for the OpenID Connect provider, to connect and to read the answer, at
 * the user info endpoint and for the discovery document and keys of service clients' issuer
 */
public final class OidcTimeout {

    /** The default timeout */
    public static final Duration DEFAULT = Duration.ofSeconds(10);

    private OidcTimeout() {}

    /**
     * The timeout of a setting
     *
     * @param value the setting, or null or blank for the default
     * @throws IllegalStateException unless it is a number of seconds above 0 that fits the HTTP
     *     clients (milliseconds as an int)
     */
    public static Duration parse(String value) {
        if (!StringUtils.hasText(value)) {
            return DEFAULT;
        }
        double seconds;
        try {
            seconds = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            seconds = -1;
        }
        if (!(seconds > 0) || seconds * 1000 > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "tesk.api.auth.oidc.timeout must be a number of seconds > 0 (at most "
                            + Integer.MAX_VALUE / 1000
                            + ")");
        }
        return Duration.ofMillis(Math.max(1, Math.round(seconds * 1000)));
    }

    /** The timeout in milliseconds, as the HTTP clients take it */
    static int millis(Duration timeout) {
        return (int) timeout.toMillis();
    }
}
