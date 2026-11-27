package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.security.PrivateKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;

/**
 * A mock OpenID Connect provider for the integration tests with authentication (the default
 * profile): its discovery and keys (on a WireMock server at {@link #PORT}), the settings that make
 * TESK API accept its tokens, and its tokens. The key is made once per JVM, so that the keys that a
 * cached application context fetched stay valid. The access matrix has its own ({@code
 * accessmatrix.MockIdp}).
 */
public final class TestIssuer {

    static final int PORT = 8090;

    /** The port of the decision service of {@link #DECISION_SERVICE} */
    static final int DECISION_SERVICE_PORT = 8094;

    /** The issuer, exactly as in {@code iss} */
    public static final String ISSUER = "http://localhost:" + PORT + "/oidc/";

    /** The client ID of the front end through which users get tokens: TESK API's audience */
    public static final String FRONT_END = "tesk-portal";

    /** A listed service client */
    public static final String CLIENT = "gateway";

    /** The settings of the issuer, for {@code @TestPropertySource} */
    public static final String ISSUER_URL = "tesk.api.auth.issuers[0].url=" + ISSUER;

    public static final String AUDIENCES = "tesk.api.auth.issuers[0].audiences=" + FRONT_END;

    /**
     * A decision service, which TESK API requires with authentication; nothing listens there unless
     * a test mocks it (AuthIT; the access matrix has its own)
     */
    public static final String DECISION_SERVICE =
            "tesk.api.authz.decision-service.url=http://localhost:"
                    + TestIssuer.DECISION_SERVICE_PORT
                    + "/access/v1/evaluation";

    public static final String SERVICE_CLIENT =
            "tesk.api.auth.issuers[0].service-clients[0].client-id=" + CLIENT;

    private static final RsaJsonWebKey KEY = generate();

    private TestIssuer() {}

    private static RsaJsonWebKey generate() {
        try {
            RsaJsonWebKey key = RsaJwkGenerator.generateJwk(2048);
            key.setKeyId("test-key");
            return key;
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The owner ID of the subject: {@code <iss>|<sub>} */
    public static String owner(String sub) {
        return ISSUER + "|" + sub;
    }

    /** Lets the server answer the discovery and the keys */
    public static void stub(WireMockExtension server) {
        server.givenThat(
                get("/oidc/.well-known/openid-configuration")
                        .willReturn(
                                okJson(
                                        "{\"issuer\":\""
                                                + ISSUER
                                                + "\",\"jwks_uri\":\""
                                                + ISSUER
                                                + "jwk\"}")));
        server.givenThat(get("/oidc/jwk").willReturn(okJson(new JsonWebKeySet(KEY).toJson())));
    }

    /** The claims of a user's access token from the front end, valid for 10 minutes */
    public static JwtClaims userClaims(String sub) {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(ISSUER);
        claims.setSubject(sub);
        claims.setAudience(FRONT_END);
        claims.setClaim("client_id", FRONT_END);
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(10);
        claims.setGeneratedJwtId();
        return claims;
    }

    /**
     * The claims of a client's access token (client credentials grant): {@code sub} = {@code
     * client_id} = {@code aud} = the client ID
     */
    public static JwtClaims clientClaims(String clientId) {
        JwtClaims claims = userClaims(clientId);
        claims.setAudience(clientId);
        claims.setClaim("client_id", clientId);
        return claims;
    }

    /** A valid access token of the user */
    public static String userToken(String sub) {
        return sign(userClaims(sub));
    }

    /** A valid access token of the client */
    public static String clientToken(String clientId) {
        return sign(clientClaims(clientId));
    }

    /** The issuer's signing key (its key ID is {@code test-key}), for tokens signed otherwise */
    public static PrivateKey privateKey() {
        return KEY.getPrivateKey();
    }

    /** The claims as an access token ({@code typ at+jwt}), signed with the issuer's key */
    public static String sign(JwtClaims claims) {
        return sign(claims, "at+jwt");
    }

    /** The claims with the header {@code typ}, signed with the issuer's key */
    public static String sign(JwtClaims claims, String type) {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setHeader("typ", type);
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        jws.setKey(KEY.getPrivateKey());
        jws.setKeyIdHeaderValue(KEY.getKeyId());
        try {
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }
}
