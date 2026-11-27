package uk.ac.ebi.tsc.tesk;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.keys.HmacKey;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Integration testing of the token validation of every request (#227, design section 3, steps 1-11)
 * with a mock issuer: valid user and service-client tokens are accepted; a token refused by any
 * step gets {@code 401} with {@code invalid_token}, without the reason or the token in the answer;
 * a token whose issuer's keys can't be fetched gets {@code 503}. The Kubernetes API is WireMocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "spring.profiles.active=auth",
            TestIssuer.ISSUER_URL,
            TestIssuer.AUDIENCES,
            TestIssuer.DECISION_SERVICE,
            TestIssuer.SERVICE_CLIENT,
            // an issuer whose keys can't be fetched: nothing listens there
            "tesk.api.auth.issuers[1].url=" + TokenValidationIT.DOWN_ISSUER,
            "tesk.api.auth.issuers[1].audiences=" + TestIssuer.FRONT_END,
            "tesk.api.auth.timeout-seconds=1"
        })
public class TokenValidationIT {

    static final String DOWN_ISSUER = "http://localhost:8094/oidc/";

    /** An issuer that TESK API doesn't accept */
    private static final String UNKNOWN_ISSUER = "http://localhost:8095/oidc/";

    private static final String INVALID_TOKEN = "Invalid access token";

    @Autowired private MockMvc mvc;

    @RegisterExtension
    public WireMockExtension mockIssuer =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(TestIssuer.PORT))
                    .failOnUnmatchedRequests(true)
                    .build();

    @RegisterExtension
    public WireMockExtension mockKubernetes =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(9000))
                    .failOnUnmatchedRequests(true)
                    .build();

    @TestConfiguration
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:9000", false);
        }
    }

    @BeforeEach
    public void stubServices() {
        TestIssuer.stub(mockIssuer);
        // no tasks, whoever asks
        mockKubernetes.givenThat(
                WireMock.get(urlPathEqualTo("/apis/batch/v1/namespaces/default/jobs"))
                        .willReturn(okJson(MockUtil.jobList())));
    }

    private ResultActions listTasks(String token) throws Exception {
        return this.mvc.perform(
                get(TASK_URL)
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON));
    }

    /** Lists the caller's tasks, selected by the hash of the owner ID {@code <iss>|<sub>} */
    private void expectAccepted(String token, String sub) throws Exception {
        listTasks(token).andExpect(status().isOk());
        mockKubernetes.verify(
                WireMock.getRequestedFor(urlPathEqualTo("/apis/batch/v1/namespaces/default/jobs"))
                        .withQueryParam(
                                "labelSelector",
                                WireMock.equalTo(
                                        MockUtil.ownTaskmastersSelector(TestIssuer.owner(sub)))));
    }

    @Test
    public void userToken_isAccepted() throws Exception {
        expectAccepted(TestIssuer.userToken("user-1"), "user-1");
    }

    /** A listed client's own token (client credentials grant: aud = its client ID) */
    @Test
    public void serviceClientToken_isAccepted() throws Exception {
        expectAccepted(TestIssuer.clientToken(TestIssuer.CLIENT), TestIssuer.CLIENT);
    }

    /** A user token with several audiences, one of them TESK API's */
    @Test
    public void userTokenWithSeveralAudiences_isAccepted() throws Exception {
        JwtClaims claims = TestIssuer.userClaims("user-1");
        claims.setAudience("other-service", TestIssuer.FRONT_END);
        expectAccepted(TestIssuer.sign(claims), "user-1");
    }

    /** A token refused by a step of the validation (design section 3) */
    record Refused(String step, Supplier<String> token) {
        @Override
        public String toString() {
            return step;
        }
    }

    private static final RsaJsonWebKey OTHER_KEY = otherKey("test-key");
    private static final RsaJsonWebKey UNKNOWN_KID_KEY = otherKey("unknown-key");

    private static RsaJsonWebKey otherKey(String keyId) {
        try {
            RsaJsonWebKey key = RsaJwkGenerator.generateJwk(2048);
            key.setKeyId(keyId);
            return key;
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signs the claims with {@code typ} as given, and the algorithm and key as given */
    private static String sign(
            JwtClaims claims, String type, String algorithm, java.security.Key key, String kid) {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setHeader("typ", type);
        jws.setAlgorithmHeaderValue(algorithm);
        jws.setAlgorithmConstraints(AlgorithmConstraints.NO_CONSTRAINTS);
        if (key != null) {
            jws.setKey(key);
        }
        if (kid != null) {
            jws.setKeyIdHeaderValue(kid);
        }
        try {
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JwtClaims user() {
        return TestIssuer.userClaims("user-1");
    }

    private static JwtClaims with(JwtClaims claims, String name, Object value) {
        claims.setClaim(name, value);
        return claims;
    }

    private static long now() {
        return NumericDate.now().getValue();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                        // step 2: a single signed JWT, with an accepted asymmetric algorithm
                        new Refused("2: not a JWT", () -> "BAR"),
                        new Refused(
                                "2: alg none",
                                () ->
                                        sign(
                                                user(),
                                                "at+jwt",
                                                AlgorithmIdentifiers.NONE,
                                                null,
                                                null)),
                        new Refused(
                                "2: HMAC",
                                () ->
                                        sign(
                                                user(),
                                                "at+jwt",
                                                AlgorithmIdentifiers.HMAC_SHA256,
                                                new HmacKey(
                                                        "a-shared-secret-of-at-least-32-bytes!!"
                                                                .getBytes(StandardCharsets.UTF_8)),
                                                "test-key")),
                        new Refused(
                                "2: algorithm not accepted (RS384)",
                                () ->
                                        sign(
                                                user(),
                                                "at+jwt",
                                                AlgorithmIdentifiers.RSA_USING_SHA384,
                                                // the issuer's own key: only the algorithm is wrong
                                                TestIssuer.privateKey(),
                                                "test-key")),
                        // step 3: the issuer
                        new Refused(
                                "3: unknown issuer",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.setIssuer(UNKNOWN_ISSUER);
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused(
                                "3: no issuer",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.unsetClaim("iss");
                                    return TestIssuer.sign(claims);
                                }),
                        // step 4: the signature
                        new Refused(
                                "4: forged signature",
                                () ->
                                        sign(
                                                user(),
                                                "at+jwt",
                                                AlgorithmIdentifiers.RSA_USING_SHA256,
                                                OTHER_KEY.getPrivateKey(),
                                                "test-key")),
                        new Refused(
                                "4: unknown key ID",
                                () ->
                                        sign(
                                                user(),
                                                "at+jwt",
                                                AlgorithmIdentifiers.RSA_USING_SHA256,
                                                UNKNOWN_KID_KEY.getPrivateKey(),
                                                "unknown-key")),
                        // step 5: an access token
                        new Refused("5: ID token (typ JWT)", () -> TestIssuer.sign(user(), "JWT")),
                        // step 6: lifetime
                        new Refused(
                                "6: expired",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.setIssuedAt(NumericDate.fromSeconds(now() - 7200));
                                    claims.setExpirationTime(NumericDate.fromSeconds(now() - 3600));
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused(
                                "6: no expiry",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.unsetClaim("exp");
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused(
                                "6: not yet valid (nbf)",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.setNotBefore(NumericDate.fromSeconds(now() + 3600));
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused(
                                "6: issued in the future",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.setIssuedAt(NumericDate.fromSeconds(now() + 3600));
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused(
                                "6: older than the maximum age",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.setIssuedAt(NumericDate.fromSeconds(now() - 2 * 86400));
                                    return TestIssuer.sign(claims);
                                }),
                        // step 7: the audience
                        new Refused(
                                "7: user token for another audience",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.setAudience("other-service");
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused(
                                "7: user token without audience",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.unsetClaim("aud");
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused(
                                "7: listed client's token for another audience",
                                () -> {
                                    JwtClaims claims = TestIssuer.clientClaims(TestIssuer.CLIENT);
                                    claims.setAudience("other-service");
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused(
                                "7: listed client's token for the front end's audience",
                                () -> {
                                    JwtClaims claims = TestIssuer.clientClaims(TestIssuer.CLIENT);
                                    claims.setAudience(TestIssuer.FRONT_END);
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused(
                                "7: unlisted client's token (its own audience)",
                                () -> TestIssuer.clientToken("unlisted-client")),
                        // step 8: the subject
                        new Refused(
                                "8: no sub",
                                () -> {
                                    JwtClaims claims = user();
                                    claims.unsetClaim("sub");
                                    return TestIssuer.sign(claims);
                                }),
                        new Refused("8: empty sub", () -> TestIssuer.sign(with(user(), "sub", ""))),
                        // step 9: classification
                        new Refused(
                                "9: user with the ID of a listed client",
                                () -> TestIssuer.userToken(TestIssuer.CLIENT)),
                        new Refused(
                                "9/10: delegated token (act), until T6",
                                () -> {
                                    Map<String, Object> act = new LinkedHashMap<>();
                                    act.put("sub", "trusted-gateway");
                                    return TestIssuer.sign(with(user(), "act", act));
                                }))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void refusedToken_isUnauthorized(Refused refused) throws Exception {
        String token = refused.token().get();
        listTasks(token)
                .andExpect(status().isUnauthorized())
                .andExpect(
                        header().string(
                                        "WWW-Authenticate",
                                        "Bearer realm=\"oauth\", error=\"invalid_token\","
                                                + " error_description=\""
                                                + INVALID_TOKEN
                                                + "\""))
                .andExpect(jsonPath("$.error").value("invalid_token"))
                .andExpect(jsonPath("$.error_description").value(INVALID_TOKEN))
                .andExpect(content().string(not(containsString(token))));
        mockKubernetes.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    /** Step 1: no token */
    @Test
    public void withoutToken_isUnauthorized() throws Exception {
        this.mvc
                .perform(get(TASK_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    /**
     * Step 4: the keys of the token's issuer can't be fetched (it is down): 503, not 401, so that
     * clients retry instead of getting a new token; nothing reaches Kubernetes
     */
    @Test
    public void unavailableKeys_areServiceUnavailable() throws Exception {
        JwtClaims claims = user();
        claims.setIssuer(DOWN_ISSUER);
        String token = TestIssuer.sign(claims);
        // also while the failed fetch is not tried again
        for (int i = 0; i < 2; i++) {
            listTasks(token)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().doesNotExist("WWW-Authenticate"))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.error").value("temporarily_unavailable"))
                    .andExpect(content().string(not(containsString(token))));
        }
        mockKubernetes.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }
}
