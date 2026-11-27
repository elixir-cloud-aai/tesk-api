package uk.ac.ebi.tsc.tesk.config.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Running without authentication is an explicit choice, and logged at startup; it refuses the
 * settings of the decision service, which needs authentication
 */
@ExtendWith(OutputCaptureExtension.class)
public class NoAuthConfigurationTest {

    @Test
    public void noAuth_isWarnedAbout(CapturedOutput output) {
        new NoAuthConfiguration(new MockEnvironment());
        assertThat(output.getOut(), containsString("WARN"));
        assertThat(output.getOut(), containsString(NoAuthConfiguration.WARNING));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "tesk.api.authz.decision-service.url",
                "tesk.api.authz.decision-service.claims[0]",
                "tesk.api.authz.operations"
            })
    public void decisionServiceSetting_isRefused(String name) {
        MockEnvironment environment = new MockEnvironment().withProperty(name, "x");
        assertThatThrownBy(() -> new NoAuthConfiguration(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(name)
                .hasMessageContaining("profile auth");
    }

    @Test
    public void decisionServiceSetting_asEnvironmentVariable_isRefused() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                // Spring reads sources by this name as environment variables
                                "test-"
                                        + StandardEnvironment
                                                .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(
                                        "TESK_API_AUTHZ_DECISIONSERVICE_URL",
                                        "https://pdp.example/")));
        assertThatThrownBy(() -> new NoAuthConfiguration(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tesk.api.authz");
    }

    @Test
    public void otherSettings_areAccepted(CapturedOutput output) {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test",
                                Map.of("tesk.api.authorization", "x", "tesk.api.auth.x", "y")));
        new NoAuthConfiguration(environment);
        assertThat(output.getOut(), containsString(NoAuthConfiguration.WARNING));
    }
}
