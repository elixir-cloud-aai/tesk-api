package uk.ac.ebi.tsc.tesk.config.security;

import org.junit.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * The setting is documented as an environment variable, in the form the Helm chart sets it
 */
public class ServiceInfoPublicSettingTest {

    @Test
    public void environmentVariable_overridesDefault() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env",
                Collections.singletonMap("TESK_API_SERVICE_INFO_PUBLIC", "false")));
        environment.getPropertySources().addLast(new MapPropertySource("defaults",
                Collections.singletonMap("tesk.api.service-info.public", "true")));
        ConfigurationPropertySources.attach(environment);

        assertEquals(Boolean.FALSE, environment.getProperty("tesk.api.service-info.public", Boolean.class));
    }
}
