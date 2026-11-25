package uk.ac.ebi.tsc.tesk.accessmatrix;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwx.HeaderParameterNames;
import org.jose4j.lang.JoseException;

/**
 * A mock OpenID Connect provider, as LS Login behaves: discovery, keys and a user info endpoint,
 * and the tokens of the matrix's callers. Its user info endpoint answers like a real provider: it
 * accepts the access tokens it issued itself while they are valid (signature, expiry, {@code typ
 * at+jwt}), whatever their audience, refuses all others (401), and answers 403 for tokens of the
 * client credentials grant (as LS Login does). The keys are made once per JVM, so that the keys
 * that a cached application context fetched stay valid.
 */
final class MockIdp {

    static final int PORT = 8091;
    static final String ISSUER = "http://localhost:" + PORT + "/oidc/";
    static final String JWKS_URI = ISSUER + "jwk";
    static final String USER_INFO_URI = ISSUER + "userinfo";

    /** An issuer that TESK doesn't know (nothing listens there) */
    static final String OTHER_ISSUER = "http://localhost:8092/oidc/";

    /** The client ID of the front end, through which users get tokens: TESK's audience */
    static final String FRONT_END = "tesk-portal";

    /** A listed service client (D-392): its tokens have {@code aud} = its own client ID */
    static final String LISTED_CLIENT = "gateway";

    /** A client that TESK doesn't list */
    static final String UNLISTED_CLIENT = "unlisted-client";

    /** A trusted actor of token exchange (RFC 8693), e.g. proTES */
    static final String TRUSTED_ACTOR = "trusted-gateway";

    /** An actor that TESK doesn't trust */
    static final String UNTRUSTED_ACTOR = "untrusted-gateway";

    private static final Gson GSON = new Gson();

    private static final RsaJsonWebKey KEY = generate("key-1");

    /** A key that the provider doesn't publish, with the same key ID */
    private static final RsaJsonWebKey FORGED_KEY = generate("key-1");

    /** A key that the provider doesn't publish, with a key ID it doesn't have */
    private static final RsaJsonWebKey UNKNOWN_KEY = generate("key-unknown");

    private MockIdp() {}

    private static RsaJsonWebKey generate(String keyId) {
        try {
            RsaJsonWebKey key = RsaJwkGenerator.generateJwk(2048);
            key.setKeyId(keyId);
            return key;
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** How a token is signed */
    enum Signature {
        VALID,
        /** by another key with the provider's key ID */
        FORGED,
        /** by a key with an unknown key ID */
        UNKNOWN_KID,
        /** not at all ({@code alg: none}) */
        NONE
    }

    /** A token as the caller sends it, and how the user info endpoint answers it */
    record Token(String value, int userInfoStatus, Map<String, Object> userInfo) {}

    /** Discovery and keys; the user info endpoint refuses tokens unless a test says otherwise */
    static void stub(WireMockExtension idp) {
        Map<String, Object> discovery = new LinkedHashMap<>();
        discovery.put("issuer", ISSUER);
        discovery.put("jwks_uri", JWKS_URI);
        discovery.put("userinfo_endpoint", USER_INFO_URI);
        idp.givenThat(
                get("/oidc/.well-known/openid-configuration")
                        .willReturn(okJson(GSON.toJson(discovery))));
        idp.givenThat(get("/oidc/jwk").willReturn(okJson(new JsonWebKeySet(KEY).toJson())));
        idp.givenThat(get("/oidc/userinfo").atPriority(10).willReturn(aResponse().withStatus(401)));
    }

    /** Lets the user info endpoint answer the token as {@link Token} says */
    static void stubUserInfo(WireMockExtension idp, Token token) {
        if (token.userInfoStatus() == 200) {
            idp.givenThat(
                    get("/oidc/userinfo")
                            .withHeader("Authorization", equalTo("Bearer " + token.value()))
                            .atPriority(1)
                            .willReturn(okJson(GSON.toJson(token.userInfo()))));
        } else {
            idp.givenThat(
                    get("/oidc/userinfo")
                            .withHeader("Authorization", equalTo("Bearer " + token.value()))
                            .atPriority(1)
                            .willReturn(aResponse().withStatus(token.userInfoStatus())));
        }
    }

    /** Lets the user info endpoint answer the token only after a delay (a hanging provider) */
    static void stubSlowUserInfo(WireMockExtension idp, Token token, int delayMs) {
        idp.givenThat(
                get("/oidc/userinfo")
                        .withHeader("Authorization", equalTo("Bearer " + token.value()))
                        .atPriority(1)
                        .willReturn(okJson(GSON.toJson(token.userInfo())).withFixedDelay(delayMs)));
    }

    /**
     * The claims of a user's access token from the front end, valid for 10 minutes: {@code aud} and
     * {@code client_id} are the front end's
     */
    static JwtClaims userClaims(String sub) {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(ISSUER);
        claims.setSubject(sub);
        claims.setAudience(FRONT_END);
        claims.setClaim("client_id", FRONT_END);
        claims.setClaim("azp", FRONT_END);
        claims.setClaim("scope", "openid profile eduperson_entitlement");
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(10);
        claims.setGeneratedJwtId();
        return claims;
    }

    /**
     * The claims of a client's access token of the client credentials grant, as LS Login issues
     * them: {@code sub} = {@code client_id} = {@code azp} = {@code aud} = the client ID
     */
    static JwtClaims clientClaims(String clientId) {
        JwtClaims claims = userClaims(clientId);
        claims.setAudience(clientId);
        claims.setClaim("client_id", clientId);
        claims.setClaim("azp", clientId);
        claims.unsetClaim("scope");
        return claims;
    }

    /** The claims of an exchanged token (RFC 8693) of the subject, obtained by the actor */
    static JwtClaims exchangedClaims(String sub, String actor) {
        JwtClaims claims = userClaims(sub);
        claims.setClaim("client_id", actor);
        claims.setClaim("azp", actor);
        Map<String, Object> act = new LinkedHashMap<>();
        act.put("sub", actor);
        claims.setClaim("act", act);
        return claims;
    }

    /** The claims of an expired token, beyond any clock skew */
    static JwtClaims expired(JwtClaims claims) {
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() - 7200));
        claims.setExpirationTime(NumericDate.fromSeconds(NumericDate.now().getValue() - 3600));
        return claims;
    }

    /**
     * A user's access token, which the user info endpoint accepts with these entitlements
     * (eduperson_entitlement)
     */
    static Token user(String sub, List<String> entitlements) {
        return accepted(sign(userClaims(sub), Signature.VALID, "at+jwt"), sub, entitlements);
    }

    /** A token that the provider issued, which the user info endpoint accepts */
    static Token accepted(String value, String sub, List<String> entitlements) {
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("sub", sub);
        userInfo.put("eduperson_entitlement", entitlements);
        return new Token(value, 200, userInfo);
    }

    /** A token that the user info endpoint answers with this status */
    static Token answered(String value, int status) {
        return new Token(value, status, Map.of());
    }

    /** Signs the claims (a JWS in compact serialisation) with the header {@code typ} */
    static String sign(JwtClaims claims, Signature signature, String type) {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setHeader(HeaderParameterNames.TYPE, type);
        try {
            switch (signature) {
                case NONE -> {
                    jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.NONE);
                    jws.setAlgorithmConstraints(AlgorithmConstraints.NO_CONSTRAINTS);
                    return jws.getCompactSerialization();
                }
                case FORGED -> sign(jws, FORGED_KEY);
                case UNKNOWN_KID -> sign(jws, UNKNOWN_KEY);
                default -> sign(jws, KEY);
            }
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Signs the claims as if issued by the other, unknown issuer, but with the provider's own key,
     * so that only the issuer is wrong
     */
    static String signAsOtherIssuer(JwtClaims claims) {
        claims.setIssuer(OTHER_ISSUER);
        return sign(claims, Signature.VALID, "at+jwt");
    }

    private static void sign(JsonWebSignature jws, RsaJsonWebKey key) {
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        jws.setKey(key.getPrivateKey());
        jws.setKeyIdHeaderValue(key.getKeyId());
    }
}
