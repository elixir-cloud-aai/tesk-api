package uk.ac.ebi.tsc.tesk.config.security;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Running without authentication is an explicit choice, and logged at startup */
@ExtendWith(OutputCaptureExtension.class)
public class NoAuthConfigurationTest {

    @Test
    public void noAuth_isWarnedAbout(CapturedOutput output) {
        new NoAuthConfiguration();
        assertThat(output.getOut(), containsString("WARN"));
        assertThat(output.getOut(), containsString(NoAuthConfiguration.WARNING));
    }
}
