package uk.ac.ebi.tsc.tesk.config.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.boot.test.system.OutputCaptureRule;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;

/** Validation of tokens at the user info endpoint (WireMocked) */
public class UserInfoTokenIntrospectorTest {

    private static final String TOKEN = "secret-token";
    private static final String PREFIX = "urn:example:group:";

    @Rule public WireMockRule userInfo = new WireMockRule(options().dynamicPort());
    @Rule public OutputCaptureRule output = new OutputCaptureRule();

    private UserInfoTokenIntrospector introspector;

    @Before
    public void setUp() {
        AuthorisationProperties properties = new AuthorisationProperties();
        properties.setBaseGroupPrefix(PREFIX + "BASE:");
        properties.setAdminSubgroup("ADMIN");
        properties.setAdminGroupFull(PREFIX + "BASE:ADMIN");
        GroupNamesSecurityExtractor groups = new GroupNamesSecurityExtractor("groups", PREFIX);
        introspector =
                new UserInfoTokenIntrospector(
                        "http://localhost:" + userInfo.port() + "/userinfo",
                        new ElixirPrincipalExtractor(groups, properties),
                        groups);
    }

    private void answer(String json) {
        userInfo.givenThat(get("/userinfo").willReturn(okJson(json)));
    }

    private void refused() {
        assertThrows(BadOpaqueTokenException.class, () -> introspector.introspect(TOKEN));
        // the refusal is logged, without the token (other loggers, e.g. of WireMock, may log it)
        List<String> logged =
                Arrays.stream(output.getOut().split("\n"))
                        .filter(
                                line ->
                                        line.contains(
                                                UserInfoTokenIntrospector.class.getSimpleName()))
                        .collect(Collectors.toList());
        assertFalse(logged.isEmpty());
        assertThat(String.join("\n", logged), not(containsString(TOKEN)));
    }

    @Test
    public void validToken_givesTheUserAndGroups() {
        answer(
                "{\"sub\":\"user@example.org\",\"name\":\"A User\",\"groups\":[\""
                        + PREFIX
                        + "BASE:TEAM\",\""
                        + PREFIX
                        + "BASE:ADMIN\"]}");

        OAuth2AuthenticatedPrincipal principal = introspector.introspect(TOKEN);

        User user = ((AuthenticatedUser) principal).getUser();
        assertEquals("user@example.org", user.getUsername());
        assertEquals("user@example.org", principal.getName());
        assertEquals("A User", user.getName());
        assertTrue(user.isTeskAdmin());
        assertTrue(user.isGroupMember("TEAM"));
        assertThat(
                principal.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList()),
                containsInAnyOrder(PREFIX + "BASE:TEAM", PREFIX + "BASE:ADMIN"));
        assertEquals("A User", principal.getAttribute("name"));
        userInfo.verify(
                getRequestedFor(urlEqualTo("/userinfo"))
                        .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                        .withHeader("Accept", equalTo("application/json")));
    }

    /** Without the groups claim, the user has the authority of a user without groups */
    @Test
    public void noGroups_givesTheUserAuthority() {
        answer("{\"sub\":\"123\"}");

        OAuth2AuthenticatedPrincipal principal = introspector.introspect(TOKEN);

        assertEquals(PREFIX + ":USER", principal.getAuthorities().iterator().next().getAuthority());
        assertFalse(((AuthenticatedUser) principal).getUser().isMember());
    }

    /** The authentication of the request has the user as principal, without the token */
    @Test
    public void authentication_hasTheUser() {
        answer("{\"sub\":\"123\",\"groups\":[\"" + PREFIX + "BASE\"]}");
        OAuth2AuthenticatedPrincipal principal = introspector.introspect(TOKEN);

        Authentication authentication = AuthenticatedUser.toAuthentication(TOKEN, principal);

        assertTrue(authentication.isAuthenticated());
        assertEquals(((AuthenticatedUser) principal).getUser(), authentication.getPrincipal());
        assertEquals("123", authentication.getName());
        assertEquals(principal.getAuthorities(), authentication.getAuthorities());
        assertFalse(String.valueOf(authentication.getCredentials()).contains(TOKEN));
    }

    @Test
    public void authentication_ofAnotherPrincipal_isRefused() {
        OAuth2AuthenticatedPrincipal other =
                new DefaultOAuth2AuthenticatedPrincipal(
                        Collections.singletonMap("sub", "123"), Collections.emptyList());
        assertThrows(
                InvalidBearerTokenException.class,
                () -> AuthenticatedUser.toAuthentication(TOKEN, other));
    }

    @Test
    public void refusedByUserInfo_isRefused() {
        userInfo.givenThat(get("/userinfo").willReturn(unauthorized()));
        refused();
    }

    @Test
    public void userInfoFails_isRefused() {
        userInfo.givenThat(get("/userinfo").willReturn(serverError()));
        refused();
    }

    @Test
    public void userInfoUnreachable_isRefused() {
        userInfo.stop();
        refused();
    }

    @Test
    public void userInfoWithError_isRefused() {
        answer("{\"sub\":\"123\",\"error\":\"invalid_token\"}");
        refused();
    }

    @Test
    public void userInfoWithoutSub_isRefused() {
        answer("{\"name\":\"A User\"}");
        refused();
    }

    @Test
    public void userInfoNotAnObject_isRefused() {
        answer("[\"sub\"]");
        refused();
    }

    @Test
    public void userInfoEmpty_isRefused() {
        userInfo.givenThat(
                get("/userinfo")
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")));
        refused();
    }

    @Test
    public void userInfoNotJson_isRefused() {
        userInfo.givenThat(
                get("/userinfo")
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "text/html")
                                        .withBody("<html></html>")));
        refused();
    }

    @Test
    public void withoutUserInfoUri_doesNotStart() {
        GroupNamesSecurityExtractor groups = new GroupNamesSecurityExtractor("groups", PREFIX);
        ElixirPrincipalExtractor extractor =
                new ElixirPrincipalExtractor(groups, new AuthorisationProperties());
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> new UserInfoTokenIntrospector(" ", extractor, groups));
        assertThat(e.getMessage(), containsString("security.oauth2.resource.user-info-uri"));
    }
}
