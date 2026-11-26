package uk.ac.ebi.tsc.tesk.config.security.token;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import java.util.Map;
import org.jose4j.jwe.ContentEncryptionAlgorithmIdentifiers;
import org.jose4j.jwe.JsonWebEncryption;
import org.jose4j.jwe.KeyManagementAlgorithmIdentifiers;
import org.jose4j.jwk.EcJwkGenerator;
import org.jose4j.jwk.EllipticCurveJsonWebKey;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.keys.EllipticCurves;
import org.jose4j.keys.HmacKey;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The JWT access token validator: tokens signed with test keys, checked against a discovery
 * document and JSON Web Key Set served by WireMock
 */
@ExtendWith(OutputCaptureExtension.class)
public class JwtAccessTokenValidatorTest {

    private static final String FRONT_END = "portal-client";
    private static final String OTHER_FRONT_END = "cli-client";
    private static final String SERVICE_CLIENT = "7c0c1fc0-e8ca-47d6-a9b2-bdc843961146";
    private static final String USER = "abc123@lifescience-ri.eu";

    private static RsaJsonWebKey key;
    private static RsaJsonWebKey otherKey;
    private static EllipticCurveJsonWebKey ecKey;

    @RegisterExtension
    public WireMockExtension idp =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().dynamicPort())
                    .failOnUnmatchedRequests(false)
                    .build();

    private TokenValidationProperties properties;

    @BeforeAll
    public static void generateKeys() throws JoseException {
        key = RsaJwkGenerator.generateJwk(2048);
        key.setKeyId("key-1");
        otherKey = RsaJwkGenerator.generateJwk(2048);
        otherKey.setKeyId("key-2");
        ecKey = EcJwkGenerator.generateJwk(EllipticCurves.P256);
        ecKey.setKeyId("key-ec");
    }

    @BeforeEach
    public void setUp() {
        properties = new TokenValidationProperties();
        properties.getIssuers().add(issuerSettings(issuer()));
        discovery("/oidc", issuer(), "/oidc/jwk");
        jwks("/oidc/jwk", key, ecKey);
    }

    private TokenValidationProperties.Issuer issuerSettings(String url) {
        TokenValidationProperties.Issuer settings = new TokenValidationProperties.Issuer();
        settings.setUrl(url);
        settings.setAudiences(List.of(FRONT_END, OTHER_FRONT_END));
        TokenValidationProperties.ServiceClient client =
                new TokenValidationProperties.ServiceClient();
        client.setClientId(SERVICE_CLIENT);
        settings.getServiceClients().add(client);
        return settings;
    }

    private String base() {
        return "http://localhost:" + idp.getPort();
    }

    private String issuer() {
        return base() + "/oidc/";
    }

    private void discovery(String path, String documentIssuer, String jwksPath) {
        idp.givenThat(
                get(path + IssuerVerifier.DISCOVERY_PATH)
                        .willReturn(
                                okJson(
                                        "{\"issuer\":\""
                                                + documentIssuer
                                                + "\",\"jwks_uri\":\""
                                                + base()
                                                + jwksPath
                                                + "\"}")));
    }

    private void jwks(String path, JsonWebKey... keys) {
        idp.givenThat(get(path).willReturn(okJson(new JsonWebKeySet(keys).toJson())));
    }

    private JwtAccessTokenValidator validator() {
        return new JwtAccessTokenValidator(properties, 100);
    }

    /** Claims of a valid user access token for the front end */
    private JwtClaims userClaims() {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(issuer());
        claims.setSubject(USER);
        claims.setAudience(FRONT_END);
        claims.setClaim("client_id", FRONT_END);
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(60);
        claims.setGeneratedJwtId();
        claims.setClaim("scope", "openid eduperson_entitlement");
        return claims;
    }

    /** Claims of a valid client credentials token of the listed service client (LS Login) */
    private JwtClaims clientClaims() {
        JwtClaims claims = userClaims();
        claims.setSubject(SERVICE_CLIENT);
        claims.setClaim("client_id", SERVICE_CLIENT);
        claims.setClaim("azp", SERVICE_CLIENT);
        claims.setAudience(SERVICE_CLIENT);
        return claims;
    }

    private static String sign(
            JwtClaims claims, PublicJsonWebKey signer, String algorithm, String type)
            throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(signer.getPrivateKey());
        jws.setKeyIdHeaderValue(signer.getKeyId());
        jws.setAlgorithmHeaderValue(algorithm);
        if (type != null) {
            jws.setHeader("typ", type);
        }
        return jws.getCompactSerialization();
    }

    private static String sign(JwtClaims claims) throws JoseException {
        return sign(claims, key, AlgorithmIdentifiers.RSA_USING_SHA256, "at+jwt");
    }

    private TokenPrincipal accepted(String token) {
        return validator().validate(token);
    }

    private String refused(String token) {
        JwtAccessTokenValidator validator = validator();
        return assertThrows(InvalidTokenException.class, () -> validator.validate(token))
                .getMessage();
    }

    private String unavailable(String token) {
        JwtAccessTokenValidator validator = validator();
        return assertThrows(IssuerUnavailableException.class, () -> validator.validate(token))
                .getMessage();
    }

    private static String startupError(TokenValidationProperties settings) {
        return assertThrows(
                        IllegalStateException.class, () -> new JwtAccessTokenValidator(settings))
                .getMessage();
    }

    // accepted tokens

    @Test
    public void userToken_isAccepted_withOwnerIssuerAndSubject(CapturedOutput output)
            throws Exception {
        String token = sign(userClaims());

        TokenPrincipal principal = accepted(token);

        assertEquals(TokenPrincipal.Kind.USER, principal.kind());
        assertEquals(issuer(), principal.issuer());
        assertEquals(USER, principal.subject());
        assertEquals(issuer() + "|" + USER, principal.owner());
        assertEquals(FRONT_END, principal.clientId());
        assertEquals("openid eduperson_entitlement", principal.claims().get("scope"));
        assertThat(output.getOut(), not(containsString(token)));
    }

    @Test
    public void userToken_isAccepted_forEachConfiguredAudience() throws Exception {
        JwtClaims claims = userClaims();
        claims.setAudience("some-other-service", OTHER_FRONT_END);

        assertEquals(USER, accepted(sign(claims)).subject());
    }

    @Test
    public void userToken_withoutClientClaim_hasNoClientId() throws Exception {
        JwtClaims claims = userClaims();
        claims.unsetClaim("client_id");

        assertNull(accepted(sign(claims)).clientId());
    }

    @Test
    public void userToken_withNonStringClientClaim_hasNoClientId() throws Exception {
        JwtClaims claims = userClaims();
        claims.setClaim("client_id", 42);

        assertNull(accepted(sign(claims)).clientId());
    }

    @Test
    public void ecdsaSignature_isAccepted() throws Exception {
        String token =
                sign(
                        userClaims(),
                        ecKey,
                        AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256,
                        "at+jwt");

        assertEquals(USER, accepted(token).subject());
    }

    @Test
    public void listedServiceClient_isAccepted_withItsOwnIdAsAudience() throws Exception {
        TokenPrincipal principal = accepted(sign(clientClaims()));

        assertEquals(TokenPrincipal.Kind.SERVICE_CLIENT, principal.kind());
        assertEquals(SERVICE_CLIENT, principal.subject());
        assertEquals(SERVICE_CLIENT, principal.clientId());
        assertEquals(issuer() + "|" + SERVICE_CLIENT, principal.owner());
    }

    @Test
    public void clientClaim_isConfigurable() throws Exception {
        properties.getIssuers().get(0).setClientClaim("azp");
        JwtClaims claims = clientClaims();
        claims.unsetClaim("client_id");

        assertEquals(TokenPrincipal.Kind.SERVICE_CLIENT, accepted(sign(claims)).kind());
    }

    @Test
    public void secondIssuer_isAccepted_withItsOwnKeys() throws Exception {
        String second = base() + "/second";
        properties.getIssuers().add(issuerSettings(second));
        discovery("/second", second, "/second/jwk");
        jwks("/second/jwk", otherKey);
        JwtClaims claims = userClaims();
        claims.setIssuer(second);

        TokenPrincipal principal =
                accepted(sign(claims, otherKey, AlgorithmIdentifiers.RSA_USING_SHA256, "at+jwt"));

        assertEquals(second + "|" + USER, principal.owner());
    }

    /** Each issuer's tokens are checked against its own keys only */
    @Test
    public void tokenOfOneIssuer_signedWithTheKeyOfAnother_isRefused() throws Exception {
        String second = base() + "/second";
        properties.getIssuers().add(issuerSettings(second));
        discovery("/second", second, "/second/jwk");
        jwks("/second/jwk", otherKey);
        JwtClaims claimsOfSecond = userClaims();
        claimsOfSecond.setIssuer(second);
        JwtAccessTokenValidator validator = validator();

        assertThrows(InvalidTokenException.class, () -> validator.validate(sign(claimsOfSecond)));
        assertThrows(
                InvalidTokenException.class,
                () ->
                        validator.validate(
                                sign(
                                        userClaims(),
                                        otherKey,
                                        AlgorithmIdentifiers.RSA_USING_SHA256,
                                        "at+jwt")));
    }

    @Test
    public void jwksUri_isUsedWithoutDiscovery_ifSet() throws Exception {
        idp.resetAll();
        jwks("/keys", key);
        properties.getIssuers().get(0).setJwksUri(base() + "/keys");

        assertEquals(USER, accepted(sign(userClaims())).subject());
        idp.verify(0, getRequestedFor(urlEqualTo("/oidc" + IssuerVerifier.DISCOVERY_PATH)));
    }

    @Test
    public void applicationAtJwtType_isAccepted() throws Exception {
        String token =
                sign(
                        userClaims(),
                        key,
                        AlgorithmIdentifiers.RSA_USING_SHA256,
                        "application/at+jwt");

        assertEquals(USER, accepted(token).subject());
    }

    @Test
    public void withoutAccessTokenType_isAccepted_ifThatCheckIsOff() throws Exception {
        properties.setRequireAccessTokenType(false);
        String token = sign(userClaims(), key, AlgorithmIdentifiers.RSA_USING_SHA256, null);

        assertEquals(USER, accepted(token).subject());
    }

    @Test
    public void keysAreFetchedOnce_forManyTokens() throws Exception {
        JwtAccessTokenValidator validator = validator();
        validator.validate(sign(userClaims()));
        validator.validate(sign(clientClaims()));

        idp.verify(1, getRequestedFor(urlEqualTo("/oidc/jwk")));
        idp.verify(1, getRequestedFor(urlEqualTo("/oidc" + IssuerVerifier.DISCOVERY_PATH)));
    }

    // refused: form and issuer

    @Test
    public void blankToken_isRefused() {
        assertEquals("no token", refused(" "));
    }

    @Test
    public void nullToken_isRefused() {
        assertEquals("no token", refused(null));
    }

    @Test
    public void opaqueToken_isRefused() {
        assertEquals("not a signed JWT", refused("2YotnFZFEjr1zCsicMWpAA"));
    }

    @Test
    public void encryptedToken_isRefused() throws Exception {
        JsonWebEncryption jwe = new JsonWebEncryption();
        jwe.setPayload(userClaims().toJson());
        jwe.setAlgorithmHeaderValue(KeyManagementAlgorithmIdentifiers.RSA_OAEP_256);
        jwe.setEncryptionMethodHeaderParameter(
                ContentEncryptionAlgorithmIdentifiers.AES_128_CBC_HMAC_SHA_256);
        jwe.setKey(key.getPublicKey());

        assertEquals("not a signed JWT", refused(jwe.getCompactSerialization()));
    }

    @Test
    public void nestedToken_isRefused() throws Exception {
        JsonWebSignature outer = new JsonWebSignature();
        outer.setPayload(sign(userClaims()));
        outer.setContentTypeHeaderValue("JWT");
        outer.setKey(key.getPrivateKey());
        outer.setKeyIdHeaderValue(key.getKeyId());
        outer.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        outer.setHeader("typ", "at+jwt");

        assertEquals("not a signed JWT", refused(outer.getCompactSerialization()));
    }

    @Test
    public void unknownIssuer_isRefused_withoutFetchingAnything() throws Exception {
        JwtClaims claims = userClaims();
        claims.setIssuer("https://evil.example.org/");

        assertEquals("unknown issuer", refused(sign(claims)));
        idp.verify(0, getRequestedFor(urlEqualTo("/oidc" + IssuerVerifier.DISCOVERY_PATH)));
    }

    /** The issuer is compared exactly, also the trailing slash */
    @Test
    public void issuerWithoutTrailingSlash_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setIssuer(issuer().replaceAll("/$", ""));

        assertEquals("unknown issuer", refused(sign(claims)));
    }

    @Test
    public void nonStringIssuer_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setClaim("iss", List.of(issuer()));

        assertEquals("unknown issuer", refused(sign(claims)));
    }

    // refused: signature and algorithm

    @Test
    public void badSignature_isRefused() throws Exception {
        String token =
                sign(userClaims(), otherKey, AlgorithmIdentifiers.RSA_USING_SHA256, "at+jwt");
        // the other key under the ID of the known one
        String forged = token.replaceFirst("^[^.]+", sign(userClaims()).split("\\.")[0]);

        assertThat(refused(forged), containsString("invalid signature"));
    }

    @Test
    public void unknownKeyId_isRefused_afterOneRefresh() throws Exception {
        String token =
                sign(userClaims(), otherKey, AlgorithmIdentifiers.RSA_USING_SHA256, "at+jwt");

        assertThat(refused(token), containsString("invalid token"));
    }

    @Test
    public void unknownKeyId_refetchesTheKeys() throws Exception {
        JwtAccessTokenValidator validator = validator();
        validator.validate(sign(userClaims()));
        jwks("/oidc/jwk", key, otherKey);
        Thread.sleep(150);

        TokenPrincipal principal =
                validator.validate(
                        sign(
                                userClaims(),
                                otherKey,
                                AlgorithmIdentifiers.RSA_USING_SHA256,
                                "at+jwt"));

        assertEquals(USER, principal.subject());
    }

    @Test
    public void unsigned_isRefused() throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(userClaims().toJson());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.NONE);
        jws.setHeader("typ", "at+jwt");
        jws.setAlgorithmConstraints(org.jose4j.jwa.AlgorithmConstraints.NO_CONSTRAINTS);

        assertThat(refused(jws.getCompactSerialization()), containsString("invalid token"));
    }

    @Test
    public void symmetricSignature_isRefused() throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(userClaims().toJson());
        jws.setKey(new HmacKey(new byte[32]));
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.HMAC_SHA256);
        jws.setHeader("typ", "at+jwt");

        assertThat(refused(jws.getCompactSerialization()), containsString("invalid token"));
    }

    @Test
    public void algorithmNotInTheAllowList_isRefused() throws Exception {
        properties.setAlgorithms(List.of("ES256"));

        assertThat(refused(sign(userClaims())), containsString("invalid token"));
    }

    // refused: type and times

    @Test
    public void idToken_isRefused_byType() throws Exception {
        String token = sign(userClaims(), key, AlgorithmIdentifiers.RSA_USING_SHA256, "JWT");

        assertThat(refused(token), containsString("not an access token (typ)"));
    }

    @Test
    public void withoutType_isRefused() throws Exception {
        String token = sign(userClaims(), key, AlgorithmIdentifiers.RSA_USING_SHA256, null);

        assertThat(refused(token), containsString("not an access token (typ)"));
    }

    @Test
    public void expired_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() - 7200));
        claims.setExpirationTime(NumericDate.fromSeconds(NumericDate.now().getValue() - 120));

        assertThat(refused(sign(claims)), containsString("expired"));
    }

    @Test
    public void justExpired_isAccepted_withinClockSkew() throws Exception {
        JwtClaims claims = userClaims();
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() - 600));
        claims.setExpirationTime(NumericDate.fromSeconds(NumericDate.now().getValue() - 30));

        assertEquals(USER, accepted(sign(claims)).subject());
    }

    @Test
    public void withoutExpiration_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.unsetClaim("exp");

        assertThat(refused(sign(claims)), containsString("no expiration time"));
    }

    @Test
    public void notYetValid_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setNotBefore(NumericDate.fromSeconds(NumericDate.now().getValue() + 600));

        assertThat(refused(sign(claims)), containsString("not yet valid"));
    }

    @Test
    public void issuedInTheFuture_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() + 600));

        assertThat(refused(sign(claims)), containsString("issued at"));
    }

    /** The clock skew is the whole leeway for iat in the future */
    @Test
    public void issuedBeyondTheClockSkew_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() + 90));

        assertThat(refused(sign(claims)), containsString("issued at"));
    }

    @Test
    public void issuedWithinTheClockSkew_isAccepted() throws Exception {
        JwtClaims claims = userClaims();
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() + 30));

        assertEquals(USER, accepted(sign(claims)).subject());
    }

    @Test
    public void tooOld_isRefused() throws Exception {
        properties.setMaxTokenAgeSeconds(3600);
        JwtClaims claims = userClaims();
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() - 7200));

        assertThat(refused(sign(claims)), containsString("issued at"));
    }

    @Test
    public void withoutIssuedAt_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.unsetClaim("iat");

        assertThat(refused(sign(claims)), containsString("issued at"));
    }

    // refused: subject, audience, classification

    @Test
    public void withoutSubject_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.unsetClaim("sub");

        assertEquals("no sub", refused(sign(claims)));
    }

    @Test
    public void blankSubject_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setSubject(" ");

        assertEquals("no sub", refused(sign(claims)));
    }

    @Test
    public void nonStringSubject_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setClaim("sub", 42);

        assertEquals("malformed claim", refused(sign(claims)));
    }

    /** Delegated tokens (RFC 8693 act) are refused until actors are supported */
    @Test
    public void delegatedToken_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setClaim("act", Map.of("sub", SERVICE_CLIENT));

        assertEquals("delegated token (act) not accepted", refused(sign(claims)));
    }

    @Test
    public void userToken_forAnotherAudience_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setAudience("some-other-service");

        assertEquals("wrong audience", refused(sign(claims)));
    }

    @Test
    public void userToken_withoutAudience_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.unsetClaim("aud");

        assertEquals("wrong audience", refused(sign(claims)));
    }

    @Test
    public void malformedAudience_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setClaim("aud", 42);

        assertEquals("wrong audience", refused(sign(claims)));
    }

    /** A service client's token for a front end is not a token for TESK */
    @Test
    public void serviceClient_withFrontEndAudience_isRefused() throws Exception {
        JwtClaims claims = clientClaims();
        claims.setAudience(FRONT_END);

        assertEquals("wrong audience", refused(sign(claims)));
    }

    /** A token of a listed client for a user (sub = the user) is a user token */
    @Test
    public void userTokenThroughListedClient_isAUserToken() throws Exception {
        JwtClaims claims = clientClaims();
        claims.setSubject(USER);
        claims.setAudience(FRONT_END);

        TokenPrincipal principal = accepted(sign(claims));

        assertEquals(TokenPrincipal.Kind.USER, principal.kind());
        assertEquals(SERVICE_CLIENT, principal.clientId());
    }

    /** ... and so it needs a front end's audience */
    @Test
    public void userTokenThroughListedClient_withClientAudience_isRefused() throws Exception {
        JwtClaims claims = clientClaims();
        claims.setSubject(USER);

        assertEquals("wrong audience", refused(sign(claims)));
    }

    /** A user whose sub is a listed client's ID would own that client's tasks (row 17) */
    @Test
    public void userWithServiceClientsId_isRefused() throws Exception {
        JwtClaims claims = userClaims();
        claims.setSubject(SERVICE_CLIENT);

        assertEquals("user with the ID of a service client", refused(sign(claims)));
    }

    @Test
    public void unlistedClient_isAUserToken_andNeedsAFrontEndAudience() throws Exception {
        JwtClaims claims = clientClaims();
        claims.setSubject("other-client");
        claims.setClaim("client_id", "other-client");
        claims.setAudience("other-client");

        assertEquals("wrong audience", refused(sign(claims)));
    }

    // service clients that may have any audience (opt-in)

    private void allowAnyAudience() {
        properties.getIssuers().get(0).getServiceClients().get(0).setAllowAnyAudience(true);
    }

    @Test
    public void allowAnyAudience_isOffByDefault() {
        assertFalse(new TokenValidationProperties.ServiceClient().isAllowAnyAudience());
    }

    @Test
    public void serviceClientWithAnyAudience_isAccepted_withItsOwnIdAsAudience() throws Exception {
        allowAnyAudience();

        assertEquals(TokenPrincipal.Kind.SERVICE_CLIENT, accepted(sign(clientClaims())).kind());
    }

    @Test
    public void serviceClientWithAnyAudience_isAccepted_forAnotherAudience() throws Exception {
        allowAnyAudience();
        JwtClaims claims = clientClaims();
        claims.setAudience("some-other-service");

        TokenPrincipal principal = accepted(sign(claims));

        assertEquals(TokenPrincipal.Kind.SERVICE_CLIENT, principal.kind());
        assertEquals(issuer() + "|" + SERVICE_CLIENT, principal.owner());
    }

    @Test
    public void serviceClientWithAnyAudience_isAccepted_withoutAudience() throws Exception {
        allowAnyAudience();
        JwtClaims claims = clientClaims();
        claims.unsetClaim("aud");

        assertEquals(TokenPrincipal.Kind.SERVICE_CLIENT, accepted(sign(claims)).kind());
    }

    @Test
    public void serviceClientWithAnyAudience_withMalformedAudience_isRefused() throws Exception {
        allowAnyAudience();
        JwtClaims claims = clientClaims();
        claims.setClaim("aud", 42);

        assertEquals("wrong audience", refused(sign(claims)));
    }

    /**
     * The opt-in is about the client's own tokens: users' tokens through it still need a front end
     */
    @Test
    public void userTokenThroughClientWithAnyAudience_needsAFrontEndAudience() throws Exception {
        allowAnyAudience();
        JwtClaims claims = clientClaims();
        claims.setSubject(USER);
        claims.setAudience("some-other-service");

        assertEquals("wrong audience", refused(sign(claims)));
    }

    @Test
    public void userWithIdOfClientWithAnyAudience_isRefused() throws Exception {
        allowAnyAudience();
        JwtClaims claims = userClaims();
        claims.setSubject(SERVICE_CLIENT);
        claims.setAudience("some-other-service");

        assertEquals("user with the ID of a service client", refused(sign(claims)));
    }

    /** Only the client with the opt-in may have any audience, not the other listed clients */
    @Test
    public void allowAnyAudience_appliesToItsClientOnly() throws Exception {
        allowAnyAudience();
        TokenValidationProperties.ServiceClient other =
                new TokenValidationProperties.ServiceClient();
        other.setClientId("other-client");
        properties.getIssuers().get(0).getServiceClients().add(other);
        JwtClaims claims = clientClaims();
        claims.setSubject("other-client");
        claims.setClaim("client_id", "other-client");
        claims.setAudience("some-other-service");

        assertEquals("wrong audience", refused(sign(claims)));
    }

    /** The warning names each client with the opt-in, with its issuer, and no other client */
    @Test
    public void allowAnyAudience_isNamedInAWarningAtStartup(CapturedOutput output) {
        allowAnyAudience();
        TokenValidationProperties.ServiceClient other =
                new TokenValidationProperties.ServiceClient();
        other.setClientId("other-client");
        properties.getIssuers().get(0).getServiceClients().add(other);

        validator();

        assertThat(output.getOut(), not(containsString("service client other-client")));

        assertThat(
                output.getOut(),
                containsString(
                        "accepts tokens of service client "
                                + SERVICE_CLIENT
                                + " of "
                                + issuer()
                                + " with any audience"));
    }

    @Test
    public void withoutAllowAnyAudience_noWarningAtStartup(CapturedOutput output) {
        validator();

        assertThat(output.getOut(), not(containsString("with any audience")));
    }

    // refused: keys unavailable

    @Test
    public void discoveryUnavailable_isRefused() throws Exception {
        idp.resetAll();
        idp.givenThat(
                get("/oidc" + IssuerVerifier.DISCOVERY_PATH)
                        .willReturn(aResponse().withStatus(503)));

        assertEquals("keys of the issuer unavailable", unavailable(sign(userClaims())));
    }

    @Test
    public void discoveryOfAnotherIssuer_isRefused() throws Exception {
        idp.resetAll();
        discovery("/oidc", "https://evil.example.org/", "/oidc/jwk");

        assertEquals("keys of the issuer unavailable", unavailable(sign(userClaims())));
    }

    @Test
    public void discoveryWithPlainHttpJwksUri_isRefused() throws Exception {
        idp.resetAll();
        idp.givenThat(
                get("/oidc" + IssuerVerifier.DISCOVERY_PATH)
                        .willReturn(
                                okJson(
                                        "{\"issuer\":\""
                                                + issuer()
                                                + "\",\"jwks_uri\":\"http://keys.example.org/jwk\"}")));

        assertEquals("keys of the issuer unavailable", unavailable(sign(userClaims())));
    }

    @Test
    public void discoveryWithoutJwksUri_isRefused() throws Exception {
        idp.resetAll();
        idp.givenThat(
                get("/oidc" + IssuerVerifier.DISCOVERY_PATH)
                        .willReturn(okJson("{\"issuer\":\"" + issuer() + "\"}")));

        assertEquals("keys of the issuer unavailable", unavailable(sign(userClaims())));
    }

    @Test
    public void emptyKeySet_isRefused() throws Exception {
        idp.resetAll();
        discovery("/oidc", issuer(), "/oidc/jwk");
        jwks("/oidc/jwk");

        assertEquals("keys of the issuer unavailable", unavailable(sign(userClaims())));
    }

    /** After a failed discovery, tokens are refused at once until the interval has passed */
    @Test
    public void failedDiscovery_isRetried_onlyAfterTheInterval() throws Exception {
        idp.resetAll();
        idp.givenThat(
                get("/oidc" + IssuerVerifier.DISCOVERY_PATH)
                        .willReturn(aResponse().withStatus(503)));
        JwtAccessTokenValidator validator = new JwtAccessTokenValidator(properties, 60_000);
        String token = sign(userClaims());

        assertThrows(IssuerUnavailableException.class, () -> validator.validate(token));
        assertThrows(IssuerUnavailableException.class, () -> validator.validate(token));

        idp.verify(1, getRequestedFor(urlEqualTo("/oidc" + IssuerVerifier.DISCOVERY_PATH)));
    }

    @Test
    public void failedDiscovery_isRetried_afterTheInterval() throws Exception {
        idp.resetAll();
        idp.givenThat(
                get("/oidc" + IssuerVerifier.DISCOVERY_PATH)
                        .willReturn(aResponse().withStatus(503)));
        JwtAccessTokenValidator validator = validator();
        String token = sign(userClaims());
        assertThrows(IssuerUnavailableException.class, () -> validator.validate(token));
        discovery("/oidc", issuer(), "/oidc/jwk");
        jwks("/oidc/jwk", key);
        Thread.sleep(150);

        assertEquals(USER, validator.validate(token).subject());
    }

    // settings checked at startup

    @Test
    public void withoutIssuers_failsAtStartup() {
        properties.getIssuers().clear();

        assertThat(startupError(properties), containsString("tesk.api.auth.issuers"));
    }

    @Test
    public void issuerWithoutUrl_failsAtStartup() {
        properties.getIssuers().get(0).setUrl(" ");

        assertThat(startupError(properties), containsString("issuers[0].url"));
    }

    @Test
    public void plainHttpIssuer_failsAtStartup() {
        properties.getIssuers().get(0).setUrl("http://login.example.org/");

        assertThat(startupError(properties), containsString("https"));
    }

    @Test
    public void plainHttpJwksUri_failsAtStartup() {
        properties.getIssuers().get(0).setJwksUri("http://login.example.org/jwk");

        assertThat(startupError(properties), containsString("issuers[0].jwks-uri"));
    }

    /** URLs can't hold the separator of issuer and subject in the owner */
    @Test
    public void issuerWithSeparator_failsAtStartup() {
        properties.getIssuers().get(0).setUrl("https://login.example.org/a|b");

        assertThat(startupError(properties), containsString("issuers[0].url"));
    }

    /** An issuer identifier has no query or fragment (RFC 8414, OpenID Connect Discovery) */
    @Test
    public void issuerWithQuery_failsAtStartup() {
        properties.getIssuers().get(0).setUrl("https://login.example.org/oidc?tenant=x");

        assertThat(startupError(properties), containsString("issuers[0].url"));
    }

    @Test
    public void issuerWithFragment_failsAtStartup() {
        properties.getIssuers().get(0).setUrl("https://login.example.org/oidc#x");

        assertThat(startupError(properties), containsString("issuers[0].url"));
    }

    @Test
    public void duplicateIssuer_failsAtStartup() {
        properties.getIssuers().add(issuerSettings(issuer()));

        assertThat(startupError(properties), containsString("twice"));
    }

    @Test
    public void issuerWithoutAudiencesOrClients_failsAtStartup() {
        properties.getIssuers().get(0).setAudiences(List.of());
        properties.getIssuers().get(0).getServiceClients().clear();

        assertThat(startupError(properties), containsString("issuers[0].audiences"));
    }

    @Test
    public void issuerWithServiceClientsOnly_isAccepted() throws Exception {
        properties.getIssuers().get(0).setAudiences(List.of());

        assertEquals(TokenPrincipal.Kind.SERVICE_CLIENT, accepted(sign(clientClaims())).kind());
        assertEquals("wrong audience", refused(sign(userClaims())));
    }

    @Test
    public void issuerWithAudiencesOnly_isAccepted() throws Exception {
        properties.getIssuers().get(0).getServiceClients().clear();

        assertEquals(TokenPrincipal.Kind.USER, accepted(sign(userClaims())).kind());
    }

    @Test
    public void blankAudience_failsAtStartup() {
        properties.getIssuers().get(0).setAudiences(List.of(FRONT_END, " "));

        assertThat(startupError(properties), containsString("issuers[0].audiences"));
    }

    @Test
    public void nullAudience_failsAtStartup() {
        properties.getIssuers().get(0).setAudiences(java.util.Arrays.asList(FRONT_END, null));

        assertThat(startupError(properties), containsString("issuers[0].audiences"));
    }

    @Test
    public void blankServiceClient_failsAtStartup() {
        properties.getIssuers().get(0).getServiceClients().get(0).setClientId("");

        assertThat(startupError(properties), containsString("service-clients[0].client-id"));
    }

    @Test
    public void duplicateServiceClient_failsAtStartup() {
        TokenValidationProperties.ServiceClient twice =
                new TokenValidationProperties.ServiceClient();
        twice.setClientId(SERVICE_CLIENT);
        properties.getIssuers().get(0).getServiceClients().add(twice);

        assertThat(startupError(properties), containsString("twice"));
    }

    @Test
    public void blankClientClaim_failsAtStartup() {
        properties.getIssuers().get(0).setClientClaim("");

        assertThat(startupError(properties), containsString("issuers[0].client-claim"));
    }

    @Test
    public void symmetricAlgorithm_failsAtStartup() {
        properties.setAlgorithms(List.of("RS256", "HS256"));

        assertThat(startupError(properties), containsString("HS256"));
    }

    @Test
    public void noneAlgorithm_failsAtStartup() {
        properties.setAlgorithms(List.of("none"));

        assertThat(startupError(properties), containsString("none"));
    }

    @Test
    public void noAlgorithms_failsAtStartup() {
        properties.setAlgorithms(List.of());

        assertThat(startupError(properties), containsString("tesk.api.auth.algorithms"));
    }

    @Test
    public void negativeClockSkew_failsAtStartup() {
        properties.setClockSkewSeconds(-1);

        assertThat(startupError(properties), containsString("clock-skew-seconds"));
    }

    @Test
    public void zeroMaxTokenAge_failsAtStartup() {
        properties.setMaxTokenAgeSeconds(0);

        assertThat(startupError(properties), containsString("max-token-age-seconds"));
    }

    @Test
    public void tooLongTimeout_failsAtStartup() {
        properties.setTimeoutSeconds(JwtAccessTokenValidator.MAX_TIMEOUT_SECONDS + 1);

        assertThat(startupError(properties), containsString("timeout-seconds"));
    }

    @Test
    public void zeroTimeout_failsAtStartup() {
        properties.setTimeoutSeconds(0);

        assertThat(startupError(properties), containsString("timeout-seconds"));
    }

    @Test
    public void settingsAreTrimmed() throws Exception {
        TokenValidationProperties.Issuer settings = properties.getIssuers().get(0);
        settings.setUrl(" " + issuer() + " ");
        settings.setAudiences(List.of(" " + FRONT_END + " "));
        settings.getServiceClients().get(0).setClientId(" " + SERVICE_CLIENT + " ");

        JwtAccessTokenValidator validator = validator();

        assertEquals(TokenPrincipal.Kind.USER, validator.validate(sign(userClaims())).kind());
        assertEquals(
                TokenPrincipal.Kind.SERVICE_CLIENT,
                validator.validate(sign(clientClaims())).kind());
    }
}
