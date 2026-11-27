package uk.ac.ebi.tsc.tesk.config.security.token;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The lookup of a caller's claims at the user info endpoint of its issuer: configured or discovered
 * endpoint, and every answer (refused: 401; unavailable: 503)
 */
public class UserInfoClientTest {

    @RegisterExtension
    static WireMockExtension idp =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String TOKEN = "token-1";

    private TokenValidationProperties properties;

    private String issuer() {
        return idp.baseUrl() + "/oidc/";
    }

    private TokenPrincipal alice() {
        return new TokenPrincipal(issuer(), "alice", TokenPrincipal.Kind.USER, "portal", Map.of());
    }

    @BeforeEach
    public void settings() {
        this.properties = new TokenValidationProperties();
        TokenValidationProperties.Issuer settings = new TokenValidationProperties.Issuer();
        settings.setUrl(issuer());
        this.properties.getIssuers().add(settings);
        // an issuer without a URL is skipped (the validator refuses it at startup)
        this.properties.getIssuers().add(new TokenValidationProperties.Issuer());
        this.properties.setTimeoutSeconds(1);
    }

    private void discovery(String body) {
        idp.stubFor(
                get(urlEqualTo("/oidc/.well-known/openid-configuration")).willReturn(okJson(body)));
    }

    private void discovered() {
        discovery(
                "{\"issuer\": \""
                        + issuer()
                        + "\", \"userinfo_endpoint\": \""
                        + idp.baseUrl()
                        + "/oidc/userinfo\"}");
    }

    private void userInfo(int status, String body) {
        idp.stubFor(
                get(urlEqualTo("/oidc/userinfo"))
                        .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                        .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private Map<String, Object> lookUp() {
        return new UserInfoClient(this.properties).claims(alice(), TOKEN);
    }

    /** The endpoint from the discovery, fetched once and kept */
    @Test
    public void claims_fromTheDiscoveredEndpoint() {
        discovered();
        userInfo(200, "{\"sub\": \"alice\", \"eduperson_entitlement\": [\"a\", \"b\"]}");
        UserInfoClient client = new UserInfoClient(this.properties);

        assertThat(client.claims(alice(), TOKEN))
                .containsEntry("sub", "alice")
                .containsEntry("eduperson_entitlement", List.of("a", "b"));
        client.claims(alice(), TOKEN);
        idp.verify(1, getRequestedFor(urlEqualTo("/oidc/.well-known/openid-configuration")));
        idp.verify(2, getRequestedFor(urlEqualTo("/oidc/userinfo")));
    }

    /** The configured endpoint, without discovery */
    @Test
    public void claims_fromTheConfiguredEndpoint() {
        this.properties.getIssuers().get(0).setUserinfoUri(" " + idp.baseUrl() + "/other ");
        idp.stubFor(
                get(urlEqualTo("/other"))
                        .willReturn(okJson("{\"sub\": \"alice\", \"name\": \"Alice\"}")));

        assertThat(lookUp()).containsEntry("name", "Alice");
        idp.verify(0, getRequestedFor(urlEqualTo("/oidc/.well-known/openid-configuration")));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    public void refused(int status) {
        discovered();
        userInfo(status, "");

        assertThatThrownBy(this::lookUp)
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("refused by the user info endpoint");
    }

    @Test
    public void anotherSubject_isRefused() {
        discovered();
        userInfo(200, "{\"sub\": \"bob\"}");

        assertThatThrownBy(this::lookUp)
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("user info of another subject");
    }

    @Test
    public void noSubject_isRefused() {
        discovered();
        userInfo(200, "{\"name\": \"Alice\"}");

        assertThatThrownBy(this::lookUp).isInstanceOf(InvalidTokenException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "eyJhbGciOiJSUzI1NiJ9.e30.c2ln", // a signed user info (application/jwt)
                "[\"alice\"]",
                "null",
                ""
            })
    public void notAJsonObject_isUnavailable(String body) {
        discovered();
        userInfo(200, body);

        assertThatThrownBy(this::lookUp)
                .isInstanceOf(IssuerUnavailableException.class)
                .hasMessage("user info unavailable: answer not a JSON object");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 404, 302})
    public void otherStatus_isUnavailable(int status) {
        discovered();
        userInfo(status, "{\"sub\": \"alice\"}");

        assertThatThrownBy(this::lookUp)
                .isInstanceOf(IssuerUnavailableException.class)
                .hasMessage("user info unavailable: status " + status);
    }

    /** No answer within the timeout (of the request or of the lookup, whichever ends first) */
    @Test
    public void timeout_isUnavailable() {
        discovered();
        idp.stubFor(
                get(urlEqualTo("/oidc/userinfo"))
                        .willReturn(okJson("{\"sub\": \"alice\"}").withFixedDelay(2500)));

        assertThatThrownBy(this::lookUp)
                .isInstanceOf(IssuerUnavailableException.class)
                .hasMessageMatching("user info unavailable: (timeout|HttpTimeoutException)");
    }

    /** An answer whose body doesn't end within the timeout (of the lookup as a whole) */
    @Test
    public void slowBody_isUnavailable() {
        discovered();
        idp.stubFor(
                get(urlEqualTo("/oidc/userinfo"))
                        .willReturn(
                                okJson("{\"sub\": \"alice\", \"padding\": \"xxxxxxxxxx\"}")
                                        .withChunkedDribbleDelay(10, 2500)));

        assertThatThrownBy(this::lookUp)
                .isInstanceOf(IssuerUnavailableException.class)
                .hasMessage("user info unavailable: timeout");
    }

    @Test
    public void connectionReset_isUnavailable() {
        discovered();
        idp.stubFor(
                get(urlEqualTo("/oidc/userinfo"))
                        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(this::lookUp).isInstanceOf(IssuerUnavailableException.class);
    }

    @Test
    public void interrupted_isUnavailable() {
        discovered();
        userInfo(200, "{\"sub\": \"alice\"}");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(this::lookUp)
                    .isInstanceOf(IssuerUnavailableException.class)
                    .hasMessage("user info unavailable: interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void unknownIssuer_isUnavailable() {
        TokenPrincipal other =
                new TokenPrincipal(
                        "https://other.example/",
                        "alice",
                        TokenPrincipal.Kind.USER,
                        null,
                        Map.of());

        assertThatThrownBy(() -> new UserInfoClient(this.properties).claims(other, TOKEN))
                .isInstanceOf(IssuerUnavailableException.class)
                .hasMessage("user info unavailable: unknown issuer");
    }

    @Test
    public void discoveryStatus_isUnavailable() {
        idp.stubFor(
                get(urlEqualTo("/oidc/.well-known/openid-configuration"))
                        .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(this::lookUp)
                .isInstanceOf(IssuerUnavailableException.class)
                .hasMessage("user info unavailable: discovery: status 500");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // another issuer
                "{\"issuer\": \"https://other.example/\", \"userinfo_endpoint\":"
                        + " \"https://other.example/userinfo\"}",
                // no endpoint
                "{\"issuer\": \"ISSUER\"}",
                // not a string
                "{\"issuer\": \"ISSUER\", \"userinfo_endpoint\": 1}",
                // plain http to another host
                "{\"issuer\": \"ISSUER\", \"userinfo_endpoint\": \"http://idp.example/userinfo\"}"
            })
    public void unusableDiscovery_isUnavailable(String document) {
        discovery(document.replace("ISSUER", issuer()));

        assertThatThrownBy(this::lookUp)
                .isInstanceOf(IssuerUnavailableException.class)
                .hasMessageContaining("discovery document");
    }
}
