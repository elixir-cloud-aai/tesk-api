package uk.ac.ebi.tsc.tesk.config.security;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** The settings of the removed group model refuse startup (issue #225) */
public class RemovedSettingsTest {

    private static StandardEnvironment properties(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    private static StandardEnvironment environmentVariables(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                // Spring reads sources by this name as environment variables
                                "test-"
                                        + StandardEnvironment
                                                .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                variables));
        return environment;
    }

    private static void refused(StandardEnvironment environment, String name) {
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class, () -> RemovedSettings.refuse(environment));
        assertThat(e.getMessage(), containsString(name));
        assertThat(e.getMessage(), containsString("removed with the group model"));
    }

    @Test
    public void groupModelSettings_areRefused() {
        for (String name :
                new String[] {
                    "tesk.api.authorisation.base-group",
                    "tesk.api.authorisation.group-prefix",
                    "tesk.api.authorisation.groups-claim",
                    "tesk.api.authorisation.admin-subgroup",
                    "tesk.api.authorisation.claim.enabled",
                    "tesk.api.authorisation.legacy-user-labels",
                    "tesk.api.authorisation.task-visibility",
                    // any other key under the prefix, e.g. misspelt
                    "tesk.api.authorisation.task-visibilty",
                    "tesk.api.authorisation"
                }) {
            refused(properties(Map.of(name, "x")), name);
        }
    }

    /** Also with an empty value, which would otherwise mean the default */
    @Test
    public void emptyValue_isRefused() {
        refused(
                properties(Map.of("tesk.api.authorisation.task-visibility", "")),
                "task-visibility");
    }

    @Test
    public void environmentVariables_areRefused() {
        refused(
                environmentVariables(Map.of("TESK_API_AUTHORISATION_BASEGROUP", "ELIXIR")),
                "tesk.api.authorisation.basegroup");
        refused(
                environmentVariables(Map.of("TESK_API_AUTHORISATION_CLAIM_VALUES_0", "a")),
                "tesk.api.authorisation.claim.values[0]");
    }

    /** The service clients are kept, until the token validator replaces them */
    @Test
    public void serviceClients_areKept() {
        assertDoesNotThrow(() ->
                RemovedSettings.refuse(
                        properties(
                                Map.of(
                                        "tesk.api.authorisation.service-clients.enabled",
                                        "true",
                                        "tesk.api.authorisation.service-clients.clients",
                                        "{}",
                                        "tesk.api.authorisation.service-clients",
                                        ""))));
        assertDoesNotThrow(() ->
                RemovedSettings.refuse(
                        environmentVariables(
                                Map.of("TESK_API_AUTHORISATION_SERVICECLIENTS_ENABLED", "true"))));
    }

    /** Other settings, also with a similar prefix, are no concern */
    @Test
    public void otherSettings_areKept() {
        assertDoesNotThrow(() ->
                RemovedSettings.refuse(
                        properties(
                                Map.of(
                                        "tesk.api.authorisationx.base-group", "x",
                                        "tesk.api.auth.issuers[0].url", "https://a",
                                        "tesk.api.authz.decision-service.url", "https://b"))));
    }
}
