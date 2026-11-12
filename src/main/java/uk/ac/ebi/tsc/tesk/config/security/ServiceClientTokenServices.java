package uk.ac.ebi.tsc.tesk.config.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.jose4j.http.Get;
import org.jose4j.http.SimpleResponse;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwk.HttpsJwks;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.ErrorCodeValidator;
import org.jose4j.jwt.consumer.ErrorCodes;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver;
import org.jose4j.keys.resolvers.VerificationKeyResolver;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.security.oauth2.provider.token.ResourceServerTokenServices;
import org.springframework.util.StringUtils;

/**
 * Accepts access tokens of allow-listed service clients (issue #153; settings in {@link
 * AuthorisationProperties.ServiceClients}), and passes all other tokens on to the token services of
 * the user info endpoint.
 *
 * <p>A token is a service client's if it is a signed JWT whose {@code iss} is the configured
 * issuer, whose client claim (by default {@code client_id}) names a listed client, and (unless that
 * check is off) whose {@code sub} is that client's ID too, as in tokens of the client credentials
 * grant. Other tokens (opaque tokens, JWTs of other issuers or other clients, tokens of users
 * obtained through a listed client) are validated at the user info endpoint, as without this
 * setting. A service client's token is then checked locally, without the user info: its signature
 * against the issuer's JSON Web Key Set (from the configured URI or the issuer's OpenID Connect
 * discovery, cached, and fetched again for an unknown key ID), with asymmetric algorithms only, the
 * exact issuer, {@code exp} (required) and {@code nbf} with a leeway, the header {@code typ} {@code
 * at+jwt} and the audience, if these are configured. If any of these fails, the request is refused
 * ({@code 401}), without asking the user info endpoint. The client is authenticated as a {@link
 * User} of its own (the client's ID, no groups, {@link User#isServiceClient()}).
 *
 * <p>Logs the client's ID for accepted and refused tokens, never the token.
 */
public class ServiceClientTokenServices implements ResourceServerTokenServices {

    private static final Logger logger = LoggerFactory.getLogger(ServiceClientTokenServices.class);

    /** Path of the OpenID Connect discovery document below the issuer */
    static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    /** Algorithms of signatures that are accepted: asymmetric ones only (no {@code none}, HMAC) */
    private static final AlgorithmConstraints ALGORITHMS =
            new AlgorithmConstraints(
                    AlgorithmConstraints.ConstraintType.PERMIT,
                    AlgorithmIdentifiers.RSA_USING_SHA256,
                    AlgorithmIdentifiers.RSA_USING_SHA384,
                    AlgorithmIdentifiers.RSA_USING_SHA512,
                    AlgorithmIdentifiers.RSA_PSS_USING_SHA256,
                    AlgorithmIdentifiers.RSA_PSS_USING_SHA384,
                    AlgorithmIdentifiers.RSA_PSS_USING_SHA512,
                    AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256,
                    AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384,
                    AlgorithmIdentifiers.ECDSA_USING_P521_CURVE_AND_SHA512);

    /**
     * Least time between two fetches of the key set for unknown key IDs, in milliseconds, so that
     * tokens with made-up key IDs can't make TESK API fetch it on every request
     */
    static final long JWKS_REFRESH_INTERVAL_MS = 10_000;

    /** Connect and read timeouts of the requests to the issuer, in milliseconds */
    private static final int HTTP_TIMEOUT_MS = 5_000;

    /** How long the keys are kept if fetching them again fails, in seconds */
    private static final long RETAIN_KEYS_ON_ERROR_SECONDS = 15 * 60;

    private final ResourceServerTokenServices userInfoTokenServices;
    private final String issuer;
    private final String jwksUri;
    private final String clientClaim;
    private final Set<String> clientIds;
    private final String audience;
    private final boolean requireAccessTokenType;
    private final boolean requireClientSubject;
    private final int clockSkewSeconds;
    private final boolean legacyUserLabels;
    private final long jwksRefreshIntervalMs;

    /** Parses tokens without checking them, to tell whether they are service clients' tokens */
    private final JwtConsumer parser =
            new JwtConsumerBuilder()
                    .setSkipAllValidators()
                    .setDisableRequireSignature()
                    .setSkipSignatureVerification()
                    .build();

    /** Checks the tokens of service clients; built on first use (it may need the discovery) */
    private volatile JwtConsumer verifier;

    /** Until when (epoch milliseconds) a failed discovery isn't tried again (under buildLock) */
    private long discoveryRetryAt = 0;

    /** Held while the verifier is built */
    private final ReentrantLock buildLock = new ReentrantLock();

    /**
     * @param userInfoTokenServices the token services of the user info endpoint, for all other
     *     tokens
     * @param properties the settings; {@code service-clients} must be enabled
     * @throws IllegalStateException if the settings lack the issuer or a client ID
     */
    public ServiceClientTokenServices(
            ResourceServerTokenServices userInfoTokenServices, AuthorisationProperties properties) {
        this(userInfoTokenServices, properties, JWKS_REFRESH_INTERVAL_MS);
    }

    /** As above, with the least time between two fetches of the key set (for tests) */
    ServiceClientTokenServices(
            ResourceServerTokenServices userInfoTokenServices,
            AuthorisationProperties properties,
            long jwksRefreshIntervalMs) {
        AuthorisationProperties.ServiceClients settings = properties.getServiceClients();
        Set<String> ids =
                Optional.ofNullable(settings.getClientIds()).orElse(Collections.emptyList())
                        .stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .collect(Collectors.toSet());
        if (!StringUtils.hasText(settings.getIssuer())
                || ids.isEmpty()
                || !StringUtils.hasText(settings.getClientClaim())) {
            // fail at startup rather than accepting no service, or (with a blank ID) any
            throw new IllegalStateException(
                    "tesk.api.authorisation.service-clients.enabled needs"
                            + " tesk.api.authorisation.service-clients.issuer, a client claim and"
                            + " at least one non-blank ID in"
                            + " tesk.api.authorisation.service-clients.client-ids");
        }
        if (settings.getClockSkewSeconds() < 0) {
            throw new IllegalStateException(
                    "tesk.api.authorisation.service-clients.clock-skew-seconds must not be"
                            + " negative");
        }
        this.userInfoTokenServices = Objects.requireNonNull(userInfoTokenServices);
        this.issuer = settings.getIssuer().trim();
        this.jwksUri =
                StringUtils.hasText(settings.getJwksUri()) ? settings.getJwksUri().trim() : null;
        this.clientClaim = settings.getClientClaim().trim();
        this.clientIds = Collections.unmodifiableSet(ids);
        this.audience =
                StringUtils.hasText(settings.getAudience()) ? settings.getAudience().trim() : null;
        this.requireAccessTokenType = settings.isRequireAccessTokenType();
        this.requireClientSubject = settings.isRequireClientSubject();
        this.clockSkewSeconds = settings.getClockSkewSeconds();
        this.legacyUserLabels = properties.isLegacyUserLabels();
        this.jwksRefreshIntervalMs = jwksRefreshIntervalMs;
    }

    @Override
    public OAuth2Authentication loadAuthentication(String accessToken)
            throws AuthenticationException, InvalidTokenException {
        String clientId = listedClient(accessToken);
        if (clientId == null) {
            OAuth2Authentication authentication =
                    this.userInfoTokenServices.loadAuthentication(accessToken);
            Object principal = authentication == null ? null : authentication.getPrincipal();
            if (principal instanceof User
                    && this.clientIds.contains(((User) principal).getUsername())) {
                // a user with a listed client's ID would share the client's tasks
                logger.warn(
                        "Refused user token: its sub is the ID of service client {}",
                        ((User) principal).getUsername());
                throw new InvalidTokenException("User ID of a service client");
            }
            return authentication;
        }
        try {
            verifier().process(accessToken);
        } catch (InvalidJwtException e) {
            logger.warn("Refused token of service client {}: {}", clientId, reasons(e));
            throw new InvalidTokenException("Invalid access token of a service client");
        } catch (IOException | JoseException e) {
            // the discovery document can't be read or has no JWKS URI
            logger.warn(
                    "Refused token of service client {}: keys of the issuer unavailable ({})",
                    clientId,
                    e.getMessage());
            throw new InvalidTokenException("Invalid access token of a service client");
        } catch (RuntimeException e) {
            // unexpected: logged with its stack trace (which doesn't hold the token)
            logger.warn("Refused token of service client {}: unexpected error", clientId, e);
            throw new InvalidTokenException("Invalid access token of a service client");
        }
        logger.info("Accepted token of service client {}", clientId);
        return authentication(clientId);
    }

    @Override
    public OAuth2AccessToken readAccessToken(String accessToken) {
        return this.userInfoTokenServices.readAccessToken(accessToken);
    }

    /**
     * The listed client that the token claims to be from, without checking the token, or null if it
     * isn't a service client's token (not a JWT, other issuer, client not listed, or a user's
     * {@code sub})
     */
    private String listedClient(String accessToken) {
        JwtClaims claims;
        try {
            JwtContext context = this.parser.process(accessToken);
            if (context.getJoseObjects().size() != 1) {
                return null; // nested JWTs aren't service clients' tokens
            }
            claims = context.getJwtClaims();
        } catch (InvalidJwtException | RuntimeException e) {
            return null; // not a JWT, e.g. an opaque token
        }
        Object iss = claims.getClaimValue("iss");
        Object client = claims.getClaimValue(this.clientClaim);
        if (!this.issuer.equals(iss)
                || !(client instanceof String)
                || !this.clientIds.contains(client)) {
            return null;
        }
        if (this.requireClientSubject && !client.equals(claims.getClaimValue("sub"))) {
            return null; // a user's token, obtained through the client
        }
        return (String) client;
    }

    /** The authentication of the client, as a principal of its own without groups */
    private OAuth2Authentication authentication(String clientId) {
        User user =
                User.builder(clientId)
                        .allGroups(Collections.emptySet())
                        .serviceClient(true)
                        .legacyUserLabels(this.legacyUserLabels)
                        .build();
        UsernamePasswordAuthenticationToken userAuthentication =
                new UsernamePasswordAuthenticationToken(user, "N/A", AuthorityUtils.NO_AUTHORITIES);
        OAuth2Request request =
                new OAuth2Request(null, clientId, null, true, null, null, null, null, null);
        return new OAuth2Authentication(request, userAuthentication);
    }

    /**
     * The verifier, built on first use. If the discovery fails, it isn't tried again for the
     * refresh interval: tokens (which may be forged, as the claims that select this path are read
     * before the signature is checked) are refused at once until then, so that they can't make
     * requests wait for the issuer one after another. While one request builds it, the others are
     * refused at once too.
     */
    private JwtConsumer verifier() throws IOException, JoseException {
        JwtConsumer consumer = this.verifier;
        if (consumer != null) {
            return consumer;
        }
        if (!this.buildLock.tryLock()) {
            throw new IOException("keys of the issuer are being fetched");
        }
        try {
            if (this.verifier == null) {
                if (System.currentTimeMillis() < this.discoveryRetryAt) {
                    throw new IOException("fetching the keys failed recently");
                }
                try {
                    this.verifier = buildVerifier(keyResolver());
                } catch (IOException | JoseException | RuntimeException e) {
                    this.discoveryRetryAt = System.currentTimeMillis() + this.jwksRefreshIntervalMs;
                    throw e;
                }
            }
            return this.verifier;
        } finally {
            this.buildLock.unlock();
        }
    }

    private VerificationKeyResolver keyResolver() throws IOException, JoseException {
        String uri = this.jwksUri != null ? this.jwksUri : discoverJwksUri();
        HttpsJwks jwks = new ThrottledHttpsJwks(uri, this.jwksRefreshIntervalMs);
        jwks.setSimpleHttpGet(httpGet());
        jwks.setRefreshReprieveThreshold(this.jwksRefreshIntervalMs);
        // keeps the keys if fetching them again fails when the cache expires
        jwks.setRetainCacheOnErrorDuration(RETAIN_KEYS_ON_ERROR_SECONDS);
        // fetched now, so that a failure is subject to the backoff of verifier(); jose4j
        // refetches an empty key set on every token, without the reprieve
        if (jwks.getJsonWebKeys().isEmpty()) {
            throw new JoseException("The key set of the issuer has no keys");
        }
        return new HttpsJwksVerificationKeyResolver(jwks);
    }

    /**
     * The issuer's key set, fetched at most once per interval, whether the last fetch succeeded or
     * not, and by one request at a time: other requests use the keys at hand meanwhile. jose4j's
     * own reprieve only applies after a successful fetch of a non-empty set, so tokens with made-up
     * key IDs could otherwise make requests wait for an unreachable issuer one after another.
     */
    static final class ThrottledHttpsJwks extends HttpsJwks {
        private final long intervalMs;
        private final ReentrantLock refreshLock = new ReentrantLock();
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

    /** HTTP client of the discovery and the key set, with short timeouts */
    private static Get httpGet() {
        Get get = new Get();
        get.setConnectTimeout(HTTP_TIMEOUT_MS);
        get.setReadTimeout(HTTP_TIMEOUT_MS);
        get.setRetries(0);
        return get;
    }

    /**
     * The JWKS URI from the issuer's discovery document, whose {@code issuer} must be the issuer
     * (OpenID Connect Discovery 1.0, section 4.3)
     */
    private String discoverJwksUri() throws IOException, JoseException {
        String url = this.issuer.replaceAll("/+$", "") + DISCOVERY_PATH;
        SimpleResponse response = httpGet().get(url);
        if (response.getStatusCode() != 200) {
            throw new IOException("Discovery answered with status " + response.getStatusCode());
        }
        Map<String, Object> document = JsonUtil.parseJson(response.getBody());
        Object jwks = document.get("jwks_uri");
        if (!this.issuer.equals(document.get("issuer")) || !(jwks instanceof String)) {
            throw new JoseException("Discovery document of another issuer or without jwks_uri");
        }
        return (String) jwks;
    }

    private JwtConsumer buildVerifier(VerificationKeyResolver keys) {
        JwtConsumerBuilder builder =
                new JwtConsumerBuilder()
                        .setVerificationKeyResolver(keys)
                        .setJwsAlgorithmConstraints(ALGORITHMS)
                        .setExpectedIssuer(true, this.issuer)
                        .setRequireExpirationTime()
                        .setAllowedClockSkewInSeconds(this.clockSkewSeconds);
        if (this.audience != null) {
            builder.setExpectedAudience(true, this.audience);
        } else {
            builder.setSkipDefaultAudienceValidation();
        }
        if (this.requireAccessTokenType) {
            builder.setExpectedType(true, "at+jwt");
        }
        return builder.build();
    }

    /** Why a token was refused, without the token or its claims */
    private static String reasons(InvalidJwtException e) {
        List<String> reasons =
                e.getErrorDetails().stream()
                        .map(ErrorCodeValidator.Error::getErrorCode)
                        .map(ServiceClientTokenServices::reason)
                        .distinct()
                        .collect(Collectors.toList());
        return reasons.isEmpty() ? "invalid token" : String.join(", ", reasons);
    }

    private static String reason(int code) {
        switch (code) {
            case ErrorCodes.EXPIRED:
                return "expired";
            case ErrorCodes.NOT_YET_VALID:
                return "not yet valid";
            case ErrorCodes.EXPIRATION_MISSING:
                return "no expiration time";
            case ErrorCodes.ISSUER_INVALID:
            case ErrorCodes.ISSUER_MISSING:
                return "wrong issuer";
            case ErrorCodes.AUDIENCE_INVALID:
            case ErrorCodes.AUDIENCE_MISSING:
                return "wrong audience";
            case ErrorCodes.SIGNATURE_INVALID:
                return "invalid signature";
            case ErrorCodes.SIGNATURE_MISSING:
                return "not signed";
            case ErrorCodes.TYPE_INVALID:
            case ErrorCodes.TYPE_MISSING:
                return "not an access token (typ)";
            default:
                return "invalid token (code " + code + ")";
        }
    }
}
