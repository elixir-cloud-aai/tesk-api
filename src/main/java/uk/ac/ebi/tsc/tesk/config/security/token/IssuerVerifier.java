package uk.ac.ebi.tsc.tesk.config.security.token;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.jose4j.http.Get;
import org.jose4j.http.SimpleResponse;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwk.HttpsJwks;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver;
import org.jose4j.keys.resolvers.VerificationKeyResolver;
import org.jose4j.lang.JoseException;
import org.jose4j.lang.UnresolvableKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies tokens of one issuer against its keys: from the configured JWKS URI or the {@code
 * jwks_uri} of its OpenID Connect discovery document, cached, and fetched again for an unknown key
 * ID at most once per interval. The key handling of the former service clients' validation (#153),
 * reused for all tokens.
 */
final class IssuerVerifier {

    private static final Logger logger = LoggerFactory.getLogger(IssuerVerifier.class);

    /** Path of the OpenID Connect discovery document below the issuer */
    static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    /**
     * Least time between two fetches of the key set (and after a failed discovery), in
     * milliseconds, so that tokens with made-up key IDs can't make TESK API fetch it on every
     * request
     */
    static final long REFRESH_INTERVAL_MS = 10_000;

    /** How long the keys are kept if fetching them again fails, in seconds */
    private static final long RETAIN_KEYS_ON_ERROR_SECONDS = 15 * 60;

    private final String issuer;
    private final String jwksUri;
    private final int timeoutMs;
    private final long refreshIntervalMs;

    /** Builds the consumer (all checks but the keys) for the key resolver */
    private final Function<VerificationKeyResolver, JwtConsumer> consumerFactory;

    /** Built on first use (it may need the discovery) */
    private volatile JwtConsumer consumer;

    /** Until when (epoch milliseconds) a failed discovery isn't tried again (under buildLock) */
    private volatile long retryAt = 0;

    /** Upper bound of the wait for the first build of the consumer, in milliseconds */
    static final long MAX_WAIT_MS = 15_000;

    /** Held while the consumer is built */
    final ReentrantLock buildLock = new ReentrantLock();

    /**
     * @param issuer the issuer, exactly as in {@code iss}
     * @param jwksUri the URI of its keys, or null to take it from the discovery
     * @param timeoutMs connect and read timeouts of the requests to the issuer
     * @param refreshIntervalMs least time between two fetches of the keys or the discovery
     * @param consumerFactory builds the consumer with all checks for the issuer's keys
     */
    IssuerVerifier(
            String issuer,
            String jwksUri,
            int timeoutMs,
            long refreshIntervalMs,
            Function<VerificationKeyResolver, JwtConsumer> consumerFactory) {
        this.issuer = issuer;
        this.jwksUri = jwksUri;
        this.timeoutMs = timeoutMs;
        this.refreshIntervalMs = refreshIntervalMs;
        this.consumerFactory = consumerFactory;
    }

    /**
     * Whether keys from the URL can be trusted: https, or http to the local host only (e.g. for
     * tests), as anyone on the network path of plain http could give TESK API keys of their own
     */
    static boolean isSecure(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(scheme)) {
            return host != null;
        }
        return "http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(host)
                        || "127.0.0.1".equals(host)
                        || "[::1]".equals(host));
    }

    /**
     * @return the claims of the token, if all checks pass
     * @throws InvalidJwtException if a check fails
     * @throws IOException if the issuer's keys are unavailable (also if they can't be fetched again
     *     later, once the cached ones have expired)
     */
    JwtClaims verify(String token) throws InvalidJwtException, IOException {
        JwtConsumer built = consumer();
        try {
            return built.processToClaims(token);
        } catch (InvalidJwtException e) {
            if (hasCause(e, KeysUnavailableException.class)) {
                throw new IOException("keys of the issuer unavailable", e);
            }
            throw e;
        }
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /** A key that couldn't be resolved because the issuer's keys couldn't be fetched */
    static final class KeysUnavailableException extends UnresolvableKeyException {
        private static final long serialVersionUID = 1L;

        KeysUnavailableException(UnresolvableKeyException e) {
            super(e.getMessage(), e);
        }
    }

    /**
     * Resolves keys with the resolver, and tells a failure to fetch the keys ({@link
     * KeysUnavailableException}) from an unknown key ID with the keys at hand
     */
    static VerificationKeyResolver signallingUnavailableKeys(VerificationKeyResolver resolver) {
        return (jws, nestingContext) -> {
            try {
                return resolver.resolveKey(jws, nestingContext);
            } catch (UnresolvableKeyException e) {
                if (e.getCause() instanceof IOException) {
                    throw new KeysUnavailableException(e);
                }
                throw e;
            }
        };
    }

    /**
     * The consumer, built on first use. Requests that arrive while the first attempt builds it wait
     * for it (at most {@link #MAX_WAIT_MS}). If building it fails (e.g. no discovery), it isn't
     * tried again for the refresh interval: tokens (which may be forged, as the issuer is read
     * before the signature is checked) are refused at once until then, so that they can't make
     * requests wait for the issuer one after another, and later attempts don't make other requests
     * wait.
     */
    private JwtConsumer consumer() throws IOException {
        JwtConsumer built = this.consumer;
        return built != null ? built : build();
    }

    private JwtConsumer build() throws IOException {
        try {
            long waitMs = this.retryAt == 0 ? Math.min(4L * this.timeoutMs, MAX_WAIT_MS) : 0;
            if (!this.buildLock.tryLock(waitMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("keys of the issuer are being fetched");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while the keys of the issuer were fetched", e);
        }
        try {
            if (this.consumer != null) {
                return this.consumer; // built by the request that held the lock
            }
            if (System.currentTimeMillis() < this.retryAt) {
                throw new IOException("fetching the keys failed recently");
            }
            JwtConsumer built;
            try {
                built = this.consumerFactory.apply(keyResolver());
            } catch (IOException | JoseException | RuntimeException e) {
                this.retryAt = System.currentTimeMillis() + this.refreshIntervalMs;
                // logged once per attempt, not per refused request
                logger.warn("Fetching the keys of issuer {} failed: {}", this.issuer, e.toString());
                throw new IOException("keys of the issuer unavailable", e);
            }
            this.consumer = built;
            return built;
        } finally {
            this.buildLock.unlock();
        }
    }

    private VerificationKeyResolver keyResolver() throws IOException, JoseException {
        String uri = this.jwksUri != null ? this.jwksUri : discoverJwksUri();
        HttpsJwks jwks = new ThrottledHttpsJwks(uri, this.refreshIntervalMs);
        jwks.setSimpleHttpGet(httpGet());
        jwks.setRefreshReprieveThreshold(this.refreshIntervalMs);
        // keeps the keys if fetching them again fails when the cache expires
        jwks.setRetainCacheOnErrorDuration(RETAIN_KEYS_ON_ERROR_SECONDS);
        // fetched now, so that a failure is subject to the backoff of consumer(); jose4j
        // refetches an empty key set on every token, without the reprieve
        if (jwks.getJsonWebKeys().isEmpty()) {
            throw new JoseException("The key set of the issuer has no keys");
        }
        return signallingUnavailableKeys(new HttpsJwksVerificationKeyResolver(jwks));
    }

    /**
     * The issuer's key set, fetched at most once per interval, whether the last fetch succeeded or
     * not, and by one request at a time: other requests use the keys at hand meanwhile. jose4j's
     * own reprieve only applies after a successful fetch of a non-empty set, so tokens with made-up
     * key IDs could otherwise make requests wait for an unreachable issuer one after another.
     */
    static final class ThrottledHttpsJwks extends HttpsJwks {
        private final long intervalMs;
        final ReentrantLock refreshLock = new ReentrantLock();

        /** When the last fetch started (epoch milliseconds); 0 for never */
        private long lastAttempt = 0;

        ThrottledHttpsJwks(String location, long intervalMs) {
            super(location);
            this.intervalMs = intervalMs;
        }

        @Override
        public void refresh() throws JoseException, IOException {
            if (!this.refreshLock.tryLock()) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                if (this.lastAttempt != 0 && now - this.lastAttempt < this.intervalMs) {
                    return;
                }
                this.lastAttempt = now;
                super.refresh();
            } finally {
                this.refreshLock.unlock();
            }
        }
    }

    /** HTTP client of the discovery and the key set, with short timeouts and no retries */
    private Get httpGet() {
        Get get = new Get();
        get.setConnectTimeout(this.timeoutMs);
        get.setReadTimeout(this.timeoutMs);
        get.setRetries(0);
        return get;
    }

    /**
     * The JWKS URI from the issuer's discovery document, whose {@code issuer} must be the issuer
     * (OpenID Connect Discovery 1.0, section 4.3), and which must be https
     */
    private String discoverJwksUri() throws IOException, JoseException {
        String url = this.issuer.replaceAll("/+$", "") + DISCOVERY_PATH;
        // throws an IOException unless the status is 200
        SimpleResponse response = httpGet().get(url);
        Map<String, Object> document = JsonUtil.parseJson(response.getBody());
        Object jwks = document.get("jwks_uri");
        if (!this.issuer.equals(document.get("issuer"))
                || !(jwks instanceof String)
                || !isSecure((String) jwks)) {
            throw new JoseException(
                    "Discovery document of another issuer, or without an https jwks_uri");
        }
        return (String) jwks;
    }
}
