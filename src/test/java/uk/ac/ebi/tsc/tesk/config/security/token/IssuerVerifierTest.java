package uk.ac.ebi.tsc.tesk.config.security.token;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.VerificationKeyResolver;
import org.jose4j.lang.UnresolvableKeyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** The key handling of an issuer: URL checks and the throttling of concurrent fetches */
public class IssuerVerifierTest {

    @RegisterExtension
    public WireMockExtension idp =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Test
    public void httpsUrls_areSecure() {
        assertTrue(IssuerVerifier.isSecure("https://login.example.org/oidc/"));
    }

    @Test
    public void httpToTheLocalHost_isSecure() {
        assertTrue(IssuerVerifier.isSecure("http://localhost:8080/"));
        assertTrue(IssuerVerifier.isSecure("http://LOCALHOST/"));
        assertTrue(IssuerVerifier.isSecure("http://127.0.0.1:8080/"));
        assertTrue(IssuerVerifier.isSecure("http://[::1]:8080/"));
    }

    @Test
    public void otherUrls_areNotSecure() {
        assertFalse(IssuerVerifier.isSecure("http://login.example.org/"));
        assertFalse(IssuerVerifier.isSecure("ftp://localhost/"));
        assertFalse(IssuerVerifier.isSecure("https:///no-host"));
        assertFalse(IssuerVerifier.isSecure("https://bad host/"));
        assertFalse(IssuerVerifier.isSecure("/relative"));
    }

    /** While one request builds the verifier, the others wait for it, and are refused after that */
    @Test
    public void whileKeysAreFetched_otherRequestsAreRefused_afterTheWait() throws Exception {
        IssuerVerifier verifier =
                new IssuerVerifier(
                        "http://localhost:" + idp.getPort() + "/",
                        null,
                        1, // timeouts of 1 ms: the others wait 4 ms
                        100,
                        keys -> new JwtConsumerBuilder().build());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            holdLock(executor, verifier.buildLock);
            IOException e = assertThrows(IOException.class, () -> verifier.verify("a.b.c"));
            assertEquals("keys of the issuer are being fetched", e.getMessage());
            idp.verify(0, getRequestedFor(urlEqualTo(IssuerVerifier.DISCOVERY_PATH)));
        } finally {
            executor.shutdownNow();
        }
    }

    /** Requests that wait while one request builds the verifier use the one it built */
    @Test
    public void waitingRequests_useTheVerifierBuiltMeanwhile() throws Exception {
        JwtConsumer built =
                new JwtConsumerBuilder()
                        .setSkipAllValidators()
                        .setDisableRequireSignature()
                        .setSkipSignatureVerification()
                        .build();
        IssuerVerifier verifier =
                new IssuerVerifier(
                        "http://localhost:" + idp.getPort() + "/", null, 5000, 100, keys -> built);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try {
            holdLock(holder, verifier.buildLock);
            Future<JwtClaims> claims = waiter.submit(() -> verifier.verify(unsigned()));
            awaitQueued(verifier.buildLock);
            ReflectionTestUtils.setField(verifier, "consumer", built);
            holder.shutdownNow();

            assertEquals("user-1", claims.get(5, TimeUnit.SECONDS).getSubject());
            idp.verify(0, getRequestedFor(urlEqualTo(IssuerVerifier.DISCOVERY_PATH)));
        } finally {
            holder.shutdownNow();
            waiter.shutdownNow();
        }
    }

    @Test
    public void interruptedWhileWaiting_isRefused() throws Exception {
        IssuerVerifier verifier =
                new IssuerVerifier(
                        "http://localhost:" + idp.getPort() + "/",
                        null,
                        5000,
                        100,
                        keys -> new JwtConsumerBuilder().build());
        ExecutorService holder = Executors.newSingleThreadExecutor();
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try {
            holdLock(holder, verifier.buildLock);
            Future<Boolean> interrupted =
                    waiter.submit(() -> {
                        IOException e =
                                assertThrows(IOException.class, () -> verifier.verify("a.b.c"));
                        assertTrue(e.getMessage().startsWith("interrupted"));
                        return Thread.currentThread().isInterrupted();
                    });
            awaitQueued(verifier.buildLock);
            waiter.shutdownNow();

            assertTrue(interrupted.get(5, TimeUnit.SECONDS));
        } finally {
            holder.shutdownNow();
            waiter.shutdownNow();
        }
    }

    /**
     * A failure to fetch the keys again later (here: for a new key ID, while the issuer is down) is
     * told from an unknown key ID, with the verifier's own key resolver
     */
    @Test
    public void keysUnavailableLater_areReportedAsUnavailable() throws Exception {
        RsaJsonWebKey current = RsaJwkGenerator.generateJwk(2048);
        current.setKeyId("current");
        RsaJsonWebKey rotated = RsaJwkGenerator.generateJwk(2048);
        rotated.setKeyId("rotated");
        IssuerVerifier verifier =
                new IssuerVerifier(
                        "http://localhost:" + idp.getPort() + "/",
                        "http://localhost:" + idp.getPort() + "/jwk",
                        1000,
                        1,
                        keys -> new JwtConsumerBuilder().setVerificationKeyResolver(keys).build());
        idp.givenThat(get("/jwk").willReturn(okJson(new JsonWebKeySet(current).toJson())));
        assertEquals("user-1", verifier.verify(signedWith(current)).getSubject());
        idp.givenThat(get("/jwk").willReturn(aResponse().withStatus(503)));
        Thread.sleep(5); // beyond the refresh interval of 1 ms

        IOException e = assertThrows(IOException.class, () -> verifier.verify(signedWith(rotated)));
        assertEquals("keys of the issuer unavailable", e.getMessage());
    }

    private static String signedWith(RsaJsonWebKey key) throws Exception {
        JwtClaims claims = new JwtClaims();
        claims.setSubject("user-1");
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(key.getPrivateKey());
        jws.setKeyIdHeaderValue(key.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        return jws.getCompactSerialization();
    }

    @Test
    public void unknownKeyId_withKeysAtHand_isAnInvalidToken() throws Exception {
        VerificationKeyResolver unknown =
                IssuerVerifier.signallingUnavailableKeys((jws, nesting) -> {
                    throw new UnresolvableKeyException("unknown kid");
                });
        IssuerVerifier verifier =
                new IssuerVerifier(
                        "http://localhost:" + idp.getPort() + "/",
                        "http://localhost:" + idp.getPort() + "/jwk",
                        1000,
                        100,
                        keys ->
                                new JwtConsumerBuilder()
                                        .setVerificationKeyResolver(unknown)
                                        .build());
        idp.givenThat(
                get("/jwk")
                        .willReturn(
                                okJson(
                                        new JsonWebKeySet(RsaJwkGenerator.generateJwk(2048))
                                                .toJson())));

        assertThrows(InvalidJwtException.class, () -> verifier.verify(signed()));
    }

    @Test
    public void keysOfTheResolver_areReturned() throws Exception {
        RsaJsonWebKey key = RsaJwkGenerator.generateJwk(2048);
        VerificationKeyResolver resolver =
                IssuerVerifier.signallingUnavailableKeys((jws, nesting) -> key.getPublicKey());

        assertEquals(key.getPublicKey(), resolver.resolveKey(null, null));
    }

    private static String signed() throws Exception {
        RsaJsonWebKey key = RsaJwkGenerator.generateJwk(2048);
        JwtClaims claims = new JwtClaims();
        claims.setSubject("user-1");
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(key.getPrivateKey());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        return jws.getCompactSerialization();
    }

    private static String unsigned() throws Exception {
        JwtClaims claims = new JwtClaims();
        claims.setSubject("user-1");
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.NONE);
        jws.setAlgorithmConstraints(AlgorithmConstraints.NO_CONSTRAINTS);
        return jws.getCompactSerialization();
    }

    /** Waits (up to 5 seconds) until a thread waits for the lock */
    private static void awaitQueued(ReentrantLock lock) throws InterruptedException {
        for (int i = 0; i < 500 && !lock.hasQueuedThreads(); i++) {
            Thread.sleep(10);
        }
        assertTrue(lock.hasQueuedThreads());
    }

    /** While one request fetches the keys, the others use the keys at hand */
    @Test
    public void whileKeysAreRefreshed_otherRequestsDontWait() throws Exception {
        idp.givenThat(
                get("/jwk")
                        .willReturn(
                                okJson(
                                        new JsonWebKeySet(RsaJwkGenerator.generateJwk(2048))
                                                .toJson())));
        IssuerVerifier.ThrottledHttpsJwks jwks =
                new IssuerVerifier.ThrottledHttpsJwks(
                        "http://localhost:" + idp.getPort() + "/jwk", 0);
        // without jose4j's own reprieve between two fetches
        jwks.setRefreshReprieveThreshold(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            holdLock(executor, jwks.refreshLock);
            jwks.refresh();
            idp.verify(0, getRequestedFor(urlEqualTo("/jwk")));
        } finally {
            executor.shutdownNow();
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        jwks.refresh();
        jwks.refresh();
        idp.verify(2, getRequestedFor(urlEqualTo("/jwk")));
    }

    /** Locks the lock in another thread, and keeps it until the executor is shut down */
    private static void holdLock(ExecutorService executor, ReentrantLock lock) throws Exception {
        Future<?> locked = executor.submit(lock::lock);
        locked.get(5, TimeUnit.SECONDS);
        executor.submit(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                lock.unlock();
            }
        });
    }
}
