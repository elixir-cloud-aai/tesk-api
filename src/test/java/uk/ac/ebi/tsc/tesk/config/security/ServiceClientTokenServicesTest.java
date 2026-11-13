package uk.ac.ebi.tsc.tesk.config.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.Arrays;
import java.util.Collections;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwk.EcJwkGenerator;
import org.jose4j.jwk.EllipticCurveJsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.keys.EllipticCurves;
import org.jose4j.keys.HmacKey;
import org.jose4j.lang.JoseException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.boot.test.system.OutputCaptureRule;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * Tokens of allow-listed service clients (issue #153), signed with test keys and checked against a
 * JSON Web Key Set and discovery document served by WireMock
 */
public class ServiceClientTokenServicesTest {

    private static final String CLIENT = "gateway-client";
    private static final String OTHER_CLIENT = "other-client";

    private static RsaJsonWebKey key;
    private static RsaJsonWebKey otherKey;

    @Rule public WireMockRule idp = new WireMockRule(options().dynamicPort());
    @Rule public OutputCaptureRule output = new OutputCaptureRule();

    private OpaqueTokenIntrospector userInfo;
    private OAuth2AuthenticatedPrincipal userPrincipal;
    private AuthorisationProperties properties;

    @BeforeClass
    public static void generateKeys() throws JoseException {
        key = RsaJwkGenerator.generateJwk(2048);
        key.setKeyId("key-1");
        otherKey = RsaJwkGenerator.generateJwk(2048);
        otherKey.setKeyId("key-2");
    }

    @Before
    public void setUp() {
        userInfo = mock(OpaqueTokenIntrospector.class);
        userPrincipal = mock(OAuth2AuthenticatedPrincipal.class);
        when(userInfo.introspect(anyString())).thenReturn(userPrincipal);
        properties = new AuthorisationProperties();
        AuthorisationProperties.ServiceClients settings = properties.getServiceClients();
        settings.setEnabled(true);
        settings.setIssuer(issuer());
        settings.setClientIds(Arrays.asList(CLIENT, "second-client"));
        discovery(issuer());
        jwks(key);
    }

    private String issuer() {
        return "http://localhost:" + idp.port() + "/oidc/";
    }

    private void discovery(String documentIssuer) {
        idp.givenThat(
                get("/oidc" + ServiceClientTokenServices.DISCOVERY_PATH)
                        .willReturn(
                                okJson(
                                        "{\"issuer\":\""
                                                + documentIssuer
                                                + "\",\"jwks_uri\":\"http://localhost:"
                                                + idp.port()
                                                + "/oidc/jwk\"}")));
    }

    private void jwks(RsaJsonWebKey... keys) {
        idp.givenThat(get("/oidc/jwk").willReturn(okJson(new JsonWebKeySet(keys).toJson())));
    }

    private ServiceClientTokenServices services() {
        return new ServiceClientTokenServices(userInfo, properties);
    }

    /** Claims of a valid token of the client credentials grant of {@code client} */
    private JwtClaims claims(String client) {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(issuer());
        claims.setClaim("client_id", client);
        claims.setSubject(client);
        claims.setAudience("tesk");
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(60);
        claims.setGeneratedJwtId();
        claims.setClaim("scope", "openid");
        return claims;
    }

    private static String sign(JwtClaims claims, RsaJsonWebKey signer, String keyId, String type)
            throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(signer.getPrivateKey());
        jws.setKeyIdHeaderValue(keyId);
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        if (type != null) {
            jws.setHeader("typ", type);
        }
        return jws.getCompactSerialization();
    }

    private static String sign(JwtClaims claims) throws JoseException {
        return sign(claims, key, key.getKeyId(), "at+jwt");
    }

    private User accepted(String token) {
        OAuth2AuthenticatedPrincipal principal = services().introspect(token);
        verify(userInfo, never()).introspect(anyString());
        assertEquals(principal.getName(), ((AuthenticatedUser) principal).getUser().getUsername());
        assertTrue(principal.getAuthorities().isEmpty());
        return ((AuthenticatedUser) principal).getUser();
    }

    private void refused(String token) {
        ServiceClientTokenServices services = services();
        assertThrows(BadOpaqueTokenException.class, () -> services.introspect(token));
        verify(userInfo, never()).introspect(anyString());
    }

    private void passedOn(String token) {
        assertSame(userPrincipal, services().introspect(token));
        verify(userInfo).introspect(token);
    }

    @Test
    public void listedClient_isAccepted_asPrincipalOfItsOwn() throws Exception {
        String token = sign(claims(CLIENT));

        User user = accepted(token);

        assertEquals(CLIENT, user.getUsername());
        assertTrue(user.isServiceClient());
        assertTrue(user.isAuthorisedWithoutGroup());
        assertFalse(user.isClaimAuthorised());
        assertFalse(user.isMember());
        assertFalse(user.isTeskAdmin());
        assertTrue(user.getAuthorities().isEmpty());
        assertThat(output.getOut(), containsString("Accepted token of service client " + CLIENT));
        assertThat(output.getOut(), not(containsString(token)));
    }

    @Test
    public void unlistedClient_isPassedOn() throws Exception {
        passedOn(sign(claims(OTHER_CLIENT)));
    }

    @Test
    public void otherIssuer_isPassedOn() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setIssuer("https://other.example.org/");
        passedOn(sign(claims));
    }

    /** The issuer is compared exactly, also with the trailing slash */
    @Test
    public void issuerWithoutTrailingSlash_isPassedOn() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setIssuer(issuer().replaceAll("/$", ""));
        passedOn(sign(claims));
    }

    @Test
    public void opaqueToken_isPassedOn() {
        passedOn("2YotnFZFEjr1zCsicMWpAA");
    }

    /** A token of a user obtained through a listed client has the user's sub */
    @Test
    public void userTokenOfListedClient_isPassedOn() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setSubject("user@example.org");
        passedOn(sign(claims));
    }

    @Test
    public void userSubject_isAccepted_ifThatCheckIsOff() throws Exception {
        properties.getServiceClients().setRequireClientSubject(false);
        JwtClaims claims = claims(CLIENT);
        claims.setSubject("user@example.org");

        assertEquals(CLIENT, accepted(sign(claims)).getUsername());
    }

    @Test
    public void expired_isRefused() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setExpirationTimeMinutesInTheFuture(-5);
        String token = sign(claims);

        refused(token);
        assertThat(output.getOut(), containsString("Refused token of service client " + CLIENT));
        assertThat(output.getOut(), containsString("expired"));
        assertThat(output.getOut(), not(containsString(token)));
    }

    /** Within the leeway (60 seconds by default), an expired token is still accepted */
    @Test
    public void justExpired_isAccepted() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() - 3600));
        claims.setExpirationTimeMinutesInTheFuture(-0.5f);
        accepted(sign(claims));
    }

    @Test
    public void withoutExpiration_isRefused() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.unsetClaim("exp");
        refused(sign(claims));
    }

    @Test
    public void notYetValid_isRefused() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setNotBeforeMinutesInThePast(-5);
        refused(sign(claims));
    }

    @Test
    public void badSignature_isRefused() throws Exception {
        // signed with another key under the ID of the issuer's key
        refused(sign(claims(CLIENT), otherKey, key.getKeyId(), "at+jwt"));
        assertThat(output.getOut(), containsString("invalid signature"));
    }

    @Test
    public void unsigned_isRefused() throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims(CLIENT).toJson());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.NONE);
        jws.setHeader("typ", "at+jwt");
        jws.setAlgorithmConstraints(AlgorithmConstraints.NO_CONSTRAINTS);
        refused(jws.getCompactSerialization());
    }

    /** HMAC signatures aren't accepted, e.g. with the public key's bytes as the secret */
    @Test
    public void symmetricSignature_isRefused() throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims(CLIENT).toJson());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.HMAC_SHA256);
        jws.setKey(new HmacKey(key.getPublicKey().getEncoded()));
        jws.setKeyIdHeaderValue(key.getKeyId());
        jws.setHeader("typ", "at+jwt");
        refused(jws.getCompactSerialization());
    }

    /** A token with a new key ID makes TESK API fetch the key set again */
    @Test
    public void unknownKeyId_refreshesTheKeys() throws Exception {
        ServiceClientTokenServices services =
                new ServiceClientTokenServices(userInfo, properties, 0);
        services.introspect(sign(claims(CLIENT)));
        // the issuer rotates its keys
        jwks(key, otherKey);

        OAuth2AuthenticatedPrincipal principal =
                services.introspect(sign(claims(CLIENT), otherKey, otherKey.getKeyId(), "at+jwt"));

        assertEquals(CLIENT, ((AuthenticatedUser) principal).getUser().getUsername());
        idp.verify(2, getRequestedFor(urlEqualTo("/oidc/jwk")));
        // the discovery document is read once
        idp.verify(
                1,
                getRequestedFor(urlEqualTo("/oidc" + ServiceClientTokenServices.DISCOVERY_PATH)));
    }

    @Test
    public void keyIdNotInTheKeySet_isRefused() throws Exception {
        refused(sign(claims(CLIENT), otherKey, otherKey.getKeyId(), "at+jwt"));
    }

    /** Unknown key IDs don't make TESK API fetch the key set more often than the interval */
    @Test
    public void unknownKeyIds_refreshTheKeysAtMostOncePerInterval() throws Exception {
        ServiceClientTokenServices services = services();
        services.introspect(sign(claims(CLIENT)));
        String unknown = sign(claims(CLIENT), otherKey, otherKey.getKeyId(), "at+jwt");
        for (int i = 0; i < 3; i++) {
            assertThrows(BadOpaqueTokenException.class, () -> services.introspect(unknown));
        }
        idp.verify(1, getRequestedFor(urlEqualTo("/oidc/jwk")));
    }

    @Test
    public void withoutAccessTokenType_isRefused() throws Exception {
        refused(sign(claims(CLIENT), key, key.getKeyId(), null));
        refused(sign(claims(CLIENT), key, key.getKeyId(), "JWT"));
    }

    @Test
    public void withoutAccessTokenType_isAccepted_ifThatCheckIsOff() throws Exception {
        properties.getServiceClients().setRequireAccessTokenType(false);
        accepted(sign(claims(CLIENT), key, key.getKeyId(), "JWT"));
    }

    @Test
    public void audience_isChecked_ifSet() throws Exception {
        properties.getServiceClients().setAudience("tesk");
        accepted(sign(claims(CLIENT)));

        JwtClaims several = claims(CLIENT);
        several.setAudience("other-service", "tesk");
        accepted(sign(several));

        JwtClaims other = claims(CLIENT);
        other.setAudience("other-service");
        refused(sign(other));

        JwtClaims none = claims(CLIENT);
        none.unsetClaim("aud");
        refused(sign(none));
    }

    /** Without the setting, any audience (or none) is accepted */
    @Test
    public void audience_isNotChecked_ifUnset() throws Exception {
        JwtClaims none = claims(CLIENT);
        none.unsetClaim("aud");
        accepted(sign(none));
    }

    @Test
    public void clientClaim_isConfigurable() throws Exception {
        properties.getServiceClients().setClientClaim("azp");
        JwtClaims claims = claims(OTHER_CLIENT);
        claims.setClaim("azp", CLIENT);
        claims.setSubject(CLIENT);

        assertEquals(CLIENT, accepted(sign(claims)).getUsername());
    }

    @Test
    public void jwksUri_isUsedWithoutDiscovery_ifSet() throws Exception {
        properties.getServiceClients().setJwksUri("http://localhost:" + idp.port() + "/oidc/jwk");

        accepted(sign(claims(CLIENT)));

        idp.verify(
                0,
                getRequestedFor(urlEqualTo("/oidc" + ServiceClientTokenServices.DISCOVERY_PATH)));
    }

    @Test
    public void discoveryOfAnotherIssuer_isRefused() throws Exception {
        discovery("https://other.example.org/");
        refused(sign(claims(CLIENT)));
        assertThat(output.getOut(), containsString("keys of the issuer unavailable"));
    }

    @Test
    public void discoveryUnavailable_isRefused() throws Exception {
        idp.givenThat(
                get("/oidc" + ServiceClientTokenServices.DISCOVERY_PATH)
                        .willReturn(aResponse().withStatus(503)));
        refused(sign(claims(CLIENT)));
    }

    @Test
    public void settingsWithoutIssuer_failAtStartup() {
        properties.getServiceClients().setIssuer(" ");
        assertThrows(IllegalStateException.class, this::services);
    }

    @Test
    public void settingsWithoutClients_failAtStartup() {
        properties.getServiceClients().setClientIds(Collections.singletonList(" "));
        assertThrows(IllegalStateException.class, this::services);
        properties.getServiceClients().setClientIds(null);
        assertThrows(IllegalStateException.class, this::services);
    }

    @Test
    public void settingsWithNegativeLeeway_failAtStartup() {
        properties.getServiceClients().setClockSkewSeconds(-1);
        assertThrows(IllegalStateException.class, this::services);
    }

    /** Tasks of a service client are selected by its ID, without a group */
    @Test
    public void serviceClient_listsItsOwnTasksWithoutGroup() throws Exception {
        User user = accepted(sign(claims(CLIENT)));
        assertEquals(
                "creator-user-hash=" + CreatorLabels.hash(CLIENT) + ",!creator-group-name",
                user.getLabelSelector());
    }

    /** A failed discovery isn't tried again within the interval: tokens are refused at once */
    @Test
    public void failedDiscovery_isNotRetriedWithinTheInterval() throws Exception {
        idp.givenThat(
                get("/oidc" + ServiceClientTokenServices.DISCOVERY_PATH)
                        .willReturn(aResponse().withStatus(503)));
        ServiceClientTokenServices services = services();
        String token = sign(claims(CLIENT));
        for (int i = 0; i < 3; i++) {
            assertThrows(BadOpaqueTokenException.class, () -> services.introspect(token));
        }
        idp.verify(
                1,
                getRequestedFor(urlEqualTo("/oidc" + ServiceClientTokenServices.DISCOVERY_PATH)));
    }

    /** ... and is tried again after it */
    @Test
    public void failedDiscovery_isRetriedAfterTheInterval() throws Exception {
        idp.givenThat(
                get("/oidc" + ServiceClientTokenServices.DISCOVERY_PATH)
                        .willReturn(aResponse().withStatus(503)));
        ServiceClientTokenServices services =
                new ServiceClientTokenServices(userInfo, properties, 0);
        String token = sign(claims(CLIENT));
        assertThrows(BadOpaqueTokenException.class, () -> services.introspect(token));
        discovery(issuer());

        services.introspect(token);
    }

    @Test
    public void emptyKeySet_isRefused() throws Exception {
        jwks();
        refused(sign(claims(CLIENT)));
    }

    /**
     * After the keys were fetched, unknown key IDs refetch them at most once per interval, also
     * while the issuer fails
     */
    @Test
    public void failingRefresh_isThrottled() throws Exception {
        ServiceClientTokenServices services =
                new ServiceClientTokenServices(userInfo, properties, 300);
        services.introspect(sign(claims(CLIENT)));
        Thread.sleep(400);
        idp.givenThat(get("/oidc/jwk").willReturn(aResponse().withStatus(503)));
        String unknown = sign(claims(CLIENT), otherKey, otherKey.getKeyId(), "at+jwt");
        for (int i = 0; i < 3; i++) {
            assertThrows(BadOpaqueTokenException.class, () -> services.introspect(unknown));
        }
        // the first fetch, and one refresh for the unknown key IDs
        idp.verify(2, getRequestedFor(urlEqualTo("/oidc/jwk")));
        // the known key still works
        services.introspect(sign(claims(CLIENT)));
    }

    /** A failed or empty key set isn't fetched again within the interval, also without discovery */
    @Test
    public void failedKeySet_isNotRefetchedWithinTheInterval() throws Exception {
        properties.getServiceClients().setJwksUri("http://localhost:" + idp.port() + "/oidc/jwk");
        String token = sign(claims(CLIENT));
        for (boolean empty : new boolean[] {false, true}) {
            idp.resetRequests();
            if (empty) {
                jwks();
            } else {
                idp.givenThat(get("/oidc/jwk").willReturn(aResponse().withStatus(503)));
            }
            ServiceClientTokenServices services = services();
            for (int i = 0; i < 3; i++) {
                assertThrows(BadOpaqueTokenException.class, () -> services.introspect(token));
            }
            idp.verify(1, getRequestedFor(urlEqualTo("/oidc/jwk")));
        }
    }

    /** ECDSA signatures are accepted, as RSA ones */
    @Test
    public void ecdsaSignature_isAccepted() throws Exception {
        EllipticCurveJsonWebKey ecKey = EcJwkGenerator.generateJwk(EllipticCurves.P256);
        ecKey.setKeyId("ec-1");
        idp.givenThat(get("/oidc/jwk").willReturn(okJson(new JsonWebKeySet(ecKey).toJson())));
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims(CLIENT).toJson());
        jws.setKey(ecKey.getPrivateKey());
        jws.setKeyIdHeaderValue(ecKey.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256);
        jws.setHeader("typ", "at+jwt");

        accepted(jws.getCompactSerialization());
    }

    /** A client claim that isn't a string names no client */
    @Test
    public void clientClaimNotAString_isPassedOn() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setStringListClaim("client_id", CLIENT);
        passedOn(sign(claims));
    }

    /** A user whose sub is a listed client's ID would share its tasks, so is refused */
    @Test
    public void userWithClientId_isRefused() {
        when(userPrincipal.getName()).thenReturn(CLIENT);
        ServiceClientTokenServices services = services();

        assertThrows(BadOpaqueTokenException.class, () -> services.introspect("opaque"));
        verify(userInfo).introspect("opaque");
    }

    @Test
    public void otherUser_isPassedOn() {
        when(userPrincipal.getName()).thenReturn("user@example.org");
        passedOn("opaque");
    }

    @Test
    public void defaults() {
        AuthorisationProperties.ServiceClients defaults =
                new AuthorisationProperties().getServiceClients();
        assertFalse(defaults.isEnabled());
        assertEquals("client_id", defaults.getClientClaim());
        assertTrue(defaults.isRequireAccessTokenType());
        assertTrue(defaults.isRequireClientSubject());
        assertEquals(60, defaults.getClockSkewSeconds());
        assertTrue(defaults.getClientIds().isEmpty());
    }

    @Test
    public void withoutIssuedAt_isRefused() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.unsetClaim("iat");
        refused(sign(claims));
    }

    @Test
    public void issuedInTheFuture_isRefused() throws Exception {
        JwtClaims claims = claims(CLIENT);
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() + 600));
        claims.setExpirationTimeMinutesInTheFuture(70);
        refused(sign(claims));
    }

    /** Keys only come from https URLs, or http to the local host */
    @Test
    public void insecureUrls() {
        assertTrue(ServiceClientTokenServices.isSecure("https://login.example.org/oidc/"));
        assertTrue(ServiceClientTokenServices.isSecure("http://localhost:8080/oidc/"));
        assertTrue(ServiceClientTokenServices.isSecure("http://127.0.0.1/jwk"));
        assertFalse(ServiceClientTokenServices.isSecure("http://login.example.org/oidc/"));
        assertFalse(ServiceClientTokenServices.isSecure("http://localhost.example.org/"));
        assertFalse(ServiceClientTokenServices.isSecure("ftp://login.example.org/"));
        assertFalse(ServiceClientTokenServices.isSecure("https:///no-host"));
        assertFalse(ServiceClientTokenServices.isSecure("not a url"));
    }

    @Test
    public void insecureIssuer_failsAtStartup() {
        properties.getServiceClients().setIssuer("http://login.example.org/oidc/");
        assertThrows(IllegalStateException.class, this::services);
    }

    @Test
    public void insecureJwksUri_failsAtStartup() {
        properties.getServiceClients().setJwksUri("http://login.example.org/jwk");
        assertThrows(IllegalStateException.class, this::services);
    }

    @Test
    public void discoveredInsecureJwksUri_isRefused() throws Exception {
        idp.givenThat(
                get("/oidc" + ServiceClientTokenServices.DISCOVERY_PATH)
                        .willReturn(
                                okJson(
                                        "{\"issuer\":\""
                                                + issuer()
                                                + "\",\"jwks_uri\":\"http://login.example.org/jwk\"}")));
        refused(sign(claims(CLIENT)));
    }

    /** Service clients never match tasks by the old creator label */
    @Test
    public void serviceClient_ignoresLegacyUserLabels() throws Exception {
        properties.setLegacyUserLabels(true);
        assertFalse(accepted(sign(claims(CLIENT))).isLegacyUserLabels());
    }
}
