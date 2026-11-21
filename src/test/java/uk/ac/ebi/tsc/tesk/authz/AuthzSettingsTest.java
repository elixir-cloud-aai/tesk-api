package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** The settings tesk.api.authz.*, also as environment variables with JSON arrays */
public class AuthzSettingsTest {

    @Test
    public void from_unset() {
        AuthzSettings settings = AuthzSettings.from(new MockEnvironment());
        assertThat(settings.getIssuer()).isNull();
        assertThat(settings.getClaimName()).isNull();
        assertThat(settings.getClaimValues()).isEmpty();
        assertThat(settings.getClaimPrefixes()).isEmpty();
        assertThat(settings.getUrl()).isNull();
        assertThat(settings.getTimeout()).isNull();
        assertThat(settings.getClaims()).isEmpty();
        assertThat(settings.getCredentialFile()).isNull();
    }

    @Test
    public void from_properties() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("tesk.api.authz.issuer", " https://idp ")
                        .withProperty("tesk.api.authz.required-claim.name", "groups")
                        .withProperty("tesk.api.authz.required-claim.values", "a, b")
                        .withProperty("tesk.api.authz.required-claim.prefixes", "urn:x:")
                        .withProperty("tesk.api.authz.decision-service.url", "http://pdp")
                        .withProperty("tesk.api.authz.decision-service.timeout", "0.5")
                        .withProperty("tesk.api.authz.decision-service.claims", "c")
                        .withProperty("tesk.api.authz.decision-service.credential-file", "/f");
        AuthzSettings settings = AuthzSettings.from(environment);
        assertThat(settings.getIssuer()).isEqualTo("https://idp");
        assertThat(settings.getClaimName()).isEqualTo("groups");
        assertThat(settings.getClaimValues()).containsExactly("a", "b");
        assertThat(settings.getClaimPrefixes()).containsExactly("urn:x:");
        assertThat(settings.getUrl()).isEqualTo("http://pdp");
        assertThat(settings.getTimeout()).isEqualTo("0.5");
        assertThat(settings.getClaims()).containsExactly("c");
        assertThat(settings.getCredentialFile()).isEqualTo("/f");
    }

    /** Lists of configuration files (YAML), as indexed properties */
    @Test
    public void from_indexedList() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("tesk.api.authz.required-claim.values[0]", "a,b")
                        .withProperty("tesk.api.authz.required-claim.values[1]", "c");
        assertThat(AuthzSettings.from(environment).getClaimValues()).containsExactly("a,b", "c");
    }

    /** Environment variables hold JSON arrays, as group names can contain commas */
    @Test
    public void from_environmentVariableWithJsonArray() {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> variables = new HashMap<>();
        variables.put("TESK_API_AUTHZ_REQUIRED_CLAIM_PREFIXES", "[\"urn:g:vo:\", \"a,b\"]");
        variables.put("TESK_API_AUTHZ_DECISION_SERVICE_CLAIMS", " [] ");
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("test", variables));
        org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(
                environment);
        AuthzSettings settings = AuthzSettings.from(environment);
        assertThat(settings.getClaimPrefixes()).containsExactly("urn:g:vo:", "a,b");
        assertThat(settings.getClaims()).isEmpty();
    }

    @Test
    public void from_malformedJsonArray() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("tesk.api.authz.required-claim.values", "[\"a\"");
        assertThatThrownBy(() -> AuthzSettings.from(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tesk.api.authz.required-claim.values");
    }

    @Test
    public void from_jsonArrayOfOtherItems() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("tesk.api.authz.required-claim.values", "[1]");
        assertThatThrownBy(() -> AuthzSettings.from(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void constructor_nullLists() {
        AuthzSettings settings = new AuthzSettings(null, " ", null, null, null, null, null, null);
        assertThat(settings.getClaimName()).isNull();
        assertThat(settings.getClaimValues()).isEmpty();
    }

    @Test
    public void list_unsetMapSource() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("m", Collections.emptyMap()));
        assertThat(AuthzSettings.list(environment, AuthzSettings.CLAIMS)).isEmpty();
    }
}
