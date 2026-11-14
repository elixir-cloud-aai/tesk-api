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

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.net.URI;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.boot.test.system.OutputCaptureRule;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

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

    /** What this class logged (other loggers, e.g. of WireMock, log requests and answers) */
    private String logged() {
        return Arrays.stream(output.getOut().split("\n"))
                .filter(line -> line.contains(UserInfoTokenIntrospector.class.getSimpleName()))
                .collect(Collectors.joining("\n"));
    }

    private void refused() {
        assertThrows(BadOpaqueTokenException.class, () -> introspector.introspect(TOKEN));
        // the refusal is logged, without the token (other loggers, e.g. of WireMock, may log it)
        String logged = logged();
        assertFalse(logged.isEmpty());
        assertThat(logged, not(containsString(TOKEN)));
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
    public void userInfoWithBlankSub_isRefused() {
        answer("{\"sub\":\" \"}");
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

    /** A refused call is logged with its status, without the answer (which may be personal) */
    @Test
    public void refusedCall_logsStatusNotAnswer() {
        userInfo.givenThat(
                get("/userinfo").willReturn(aResponse().withStatus(500).withBody("ANSWER-MARKER")));
        refused();
        String logged = logged();
        assertThat(logged, containsString("Could not fetch user details: status 500"));
        assertThat(logged, not(containsString("ANSWER-MARKER")));
    }

    @Test
    public void connectionReset_isRefused() {
        userInfo.givenThat(
                get("/userinfo").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        refused();
    }

    /** A user info endpoint that doesn't answer in time refuses the token */
    @Test
    public void slowUserInfo_isRefused() {
        userInfo.givenThat(
                get("/userinfo").willReturn(okJson("{\"sub\":\"123\"}").withFixedDelay(2_000)));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(200);
        GroupNamesSecurityExtractor groups = new GroupNamesSecurityExtractor("groups", PREFIX);
        introspector =
                new UserInfoTokenIntrospector(
                        "http://localhost:" + userInfo.port() + "/userinfo",
                        new ElixirPrincipalExtractor(groups, new AuthorisationProperties()),
                        groups,
                        new RestTemplate(factory));
        refused();
    }

    /** The client of the user info endpoint has timeouts */
    @Test
    public void restTemplate_hasTimeouts() {
        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory)
                        UserInfoTokenIntrospector.restTemplate().getRequestFactory();
        assertEquals(
                UserInfoTokenIntrospector.CONNECT_TIMEOUT_MS,
                ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(
                UserInfoTokenIntrospector.READ_TIMEOUT_MS,
                ReflectionTestUtils.getField(factory, "readTimeout"));
    }

    /** Host names that URI doesn't parse as hosts (e.g. with _, as in Docker Compose) work */
    @Test
    public void userInfoUriWithUnderscore_starts() {
        GroupNamesSecurityExtractor groups = new GroupNamesSecurityExtractor("groups", PREFIX);
        new UserInfoTokenIntrospector(
                "http://my_idp:8080/userinfo",
                new ElixirPrincipalExtractor(groups, new AuthorisationProperties()),
                groups);
    }

    @Test
    public void hasHost() {
        assertTrue(UserInfoTokenIntrospector.hasHost(URI.create("https://login.example.org/")));
        assertTrue(UserInfoTokenIntrospector.hasHost(URI.create("http://my_idp:8080/")));
        assertFalse(UserInfoTokenIntrospector.hasHost(URI.create("http://:8080/")));
        // not a URL that Java can open
        assertFalse(UserInfoTokenIntrospector.hasHost(URI.create("unknown://host/")));
        assertFalse(UserInfoTokenIntrospector.hasHost(URI.create("relative/path")));
    }

    @Test
    public void userInfoUriNotAbsoluteHttp_doesNotStart() {
        GroupNamesSecurityExtractor groups = new GroupNamesSecurityExtractor("groups", PREFIX);
        ElixirPrincipalExtractor extractor =
                new ElixirPrincipalExtractor(groups, new AuthorisationProperties());
        for (String uri :
                new String[] {
                    "login.example.org/oidc/userinfo",
                    "ftp://login.example.org/",
                    "http:userinfo",
                    "http://:8080/userinfo",
                    "http://user@/userinfo"
                }) {
            IllegalStateException e =
                    assertThrows(
                            IllegalStateException.class,
                            () -> new UserInfoTokenIntrospector(uri, extractor, groups));
            assertThat(e.getMessage(), containsString("absolute http(s) URL"));
        }
    }
}
