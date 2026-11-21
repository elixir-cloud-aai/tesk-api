package uk.ac.ebi.tsc.tesk.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The setting tesk.api.auth.oidc.timeout: seconds, more than 0 */
public class OidcTimeoutTest {

    @Test
    public void parse_default() {
        assertThat(OidcTimeout.parse(null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(OidcTimeout.parse(" ")).isEqualTo(Duration.ofSeconds(10));
        assertThat(OidcTimeout.DEFAULT).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    public void parse_seconds() {
        assertThat(OidcTimeout.parse("3")).isEqualTo(Duration.ofSeconds(3));
        assertThat(OidcTimeout.parse(" 0.25 ")).isEqualTo(Duration.ofMillis(250));
    }

    /** Below a millisecond, the timeout is a millisecond (0 would mean none) */
    @Test
    public void parse_belowAMillisecond() {
        assertThat(OidcTimeout.parse("0.0001")).isEqualTo(Duration.ofMillis(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "x", "Infinity", "NaN", "1e10"})
    public void parse_invalid(String value) {
        assertThatThrownBy(() -> OidcTimeout.parse(value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tesk.api.auth.oidc.timeout");
    }

    @Test
    public void millis() {
        assertThat(OidcTimeout.millis(Duration.ofMillis(2500))).isEqualTo(2500);
    }
}
