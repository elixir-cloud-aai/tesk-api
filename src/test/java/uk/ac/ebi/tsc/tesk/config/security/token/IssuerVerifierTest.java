package uk.ac.ebi.tsc.tesk.config.security.token;

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
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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

    /** While one request builds the verifier, the others are refused at once */
    @Test
    public void whileKeysAreFetched_otherRequestsAreRefused() throws Exception {
        IssuerVerifier verifier =
                new IssuerVerifier(
                        "http://localhost:" + idp.getPort() + "/",
                        null,
                        1000,
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
