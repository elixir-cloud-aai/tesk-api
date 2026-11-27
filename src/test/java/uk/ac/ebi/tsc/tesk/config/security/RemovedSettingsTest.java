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

/**
 * The settings of the removed group model (issue #225) and service clients (#227) refuse startup
 */
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

    /** The service clients' settings, and what replaces each */
    private static final Map<String, String> SERVICE_CLIENT_SETTINGS =
            Map.of(
                    "enabled", "tesk.api.auth.issuers[i].service-clients[j].client-id",
                    "issuer", "tesk.api.auth.issuers[i].url",
                    "jwks-uri", "tesk.api.auth.issuers[i].jwks-uri",
                    "client-claim", "tesk.api.auth.issuers[i].client-claim",
                    "clients", "tesk.api.auth.issuers[i].service-clients[j].client-id",
                    "require-access-token-type", "tesk.api.auth.require-access-token-type",
                    "require-client-subject", "sub must be the client ID",
                    "clock-skew-seconds", "tesk.api.auth.clock-skew-seconds");

    private static void refusedServiceClient(
            StandardEnvironment environment, String name, String replacement) {
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class, () -> RemovedSettings.refuse(environment));
        assertThat(e.getMessage(), containsString(name));
        assertThat(e.getMessage(), containsString("removed with the service clients"));
        assertThat(e.getMessage(), containsString(replacement));
    }

    /**
     * The service clients of #153 and #220 are removed too (#227), each setting naming what
     * replaces it, as property
     */
    @Test
    public void serviceClients_areRefusedWithTheirReplacement() {
        SERVICE_CLIENT_SETTINGS.forEach((setting, replacement) -> {
            String name = "tesk.api.authorisation.service-clients." + setting;
            refusedServiceClient(properties(Map.of(name, "x")), name, replacement);
        });
        refusedServiceClient(
                properties(Map.of("tesk.api.authorisation.serviceClients.jwksUri", "x")),
                "tesk.api.authorisation.serviceclients.jwksuri",
                "tesk.api.auth.issuers[i].jwks-uri");
    }

    /** ... and as environment variable, with or without {@code _} between the words */
    @Test
    public void serviceClientsEnvironmentVariables_areRefusedWithTheirReplacement() {
        SERVICE_CLIENT_SETTINGS.forEach((setting, replacement) -> {
            String variable = setting.toUpperCase(java.util.Locale.ROOT).replace("-", "_");
            refusedServiceClient(
                    environmentVariables(
                            Map.of("TESK_API_AUTHORISATION_SERVICE_CLIENTS_" + variable, "x")),
                    "tesk.api.authorisation.service.clients.",
                    replacement);
            refusedServiceClient(
                    environmentVariables(
                            Map.of("TESK_API_AUTHORISATION_SERVICECLIENTS_" + variable, "x")),
                    "tesk.api.authorisation.serviceclients.",
                    replacement);
        });
    }

    /** The prefix itself, lists and unknown keys under it name the new service clients */
    @Test
    public void otherServiceClientsSettings_nameTheNewServiceClients() {
        for (String name :
                new String[] {
                    "tesk.api.authorisation.service-clients",
                    "tesk.api.authorisation.service-clients[0]",
                    "tesk.api.authorisation.service-clients.audiences"
                }) {
            refusedServiceClient(
                    properties(Map.of(name, "x")),
                    name,
                    RemovedSettings.ANY_SERVICE_CLIENT_SETTING);
        }
    }

    /** Words that merely start alike belong to the group model's settings */
    @Test
    public void similarNames_areGroupModelSettings() {
        refused(
                environmentVariables(Map.of("TESK_API_AUTHORISATION_SERVICE", "x")),
                "tesk.api.authorisation.service");
        refused(
                environmentVariables(
                        Map.of("TESK_API_AUTHORISATION_SERVICE_CLIENTSX_ENABLED", "x")),
                "clientsx");
        refused(properties(Map.of("tesk.api.authorisation.service-clientsx", "x")), "clientsx");
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
