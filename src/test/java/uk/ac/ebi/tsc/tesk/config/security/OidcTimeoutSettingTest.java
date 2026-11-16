package uk.ac.ebi.tsc.tesk.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import org.jose4j.http.Get;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** tesk.api.auth.oidc.timeout reaches the user info endpoint's and the issuer's clients */
public class OidcTimeoutSettingTest {

    private static final String USER_INFO = "https://login.example.org/userinfo";

    private static ResourceServerConfiguration configuration(
            AuthorisationProperties properties, String timeout) {
        return new ResourceServerConfiguration(
                null, null, properties, "/ga4gh/tes/v1", true, USER_INFO, timeout);
    }

    private static Object readTimeout(OpaqueTokenIntrospector userInfo) {
        RestTemplate template =
                (RestTemplate) ReflectionTestUtils.getField(userInfo, "restOperations");
        return ReflectionTestUtils.getField(template.getRequestFactory(), "readTimeout");
    }

    @Test
    public void userInfo_getsTimeout() {
        OpaqueTokenIntrospector introspector =
                configuration(new AuthorisationProperties(), "2.5").tokenIntrospector();
        assertThat(introspector).isInstanceOf(UserInfoTokenIntrospector.class);
        assertThat(readTimeout(introspector)).isEqualTo(2500);
    }

    @Test
    public void serviceClients_getTimeout() {
        AuthorisationProperties properties = new AuthorisationProperties();
        properties.getServiceClients().setEnabled(true);
        properties.getServiceClients().setIssuer("https://login.example.org/");
        properties.getServiceClients().setClientIds(Collections.singletonList("gateway"));
        ServiceClientTokenServices services =
                (ServiceClientTokenServices) configuration(properties, "2.5").tokenIntrospector();
        Get get = services.httpGet();
        assertThat(ReflectionTestUtils.getField(get, "readTimeout")).isEqualTo(2500);
        assertThat(
                        readTimeout(
                                (OpaqueTokenIntrospector)
                                        ReflectionTestUtils.getField(
                                                services, "userInfoIntrospector")))
                .isEqualTo(2500);
    }

    @Test
    public void invalidTimeout_stopsStartup() {
        assertThatThrownBy(() -> configuration(new AuthorisationProperties(), "0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tesk.api.auth.oidc.timeout");
    }
}
