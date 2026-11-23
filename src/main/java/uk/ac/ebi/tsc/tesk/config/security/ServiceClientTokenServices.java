package uk.ac.ebi.tsc.tesk.config.security;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.jose4j.jwt.MalformedClaimException;
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
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accepts access tokens of allow-listed service clients (issue #153; settings in {@link
 * AuthorisationProperties.ServiceClients}), and passes all other tokens on to the user info
 * endpoint ({@link UserInfoTokenIntrospector}).
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
 * at+jwt}, if configured, and the audience: {@code aud} must be or contain one of the audiences of
 * that client (issue #215), unless the client may have any. If any of these fails, the request is
 * refused ({@code 401}), without asking the user info endpoint. The client is authenticated as a
 * {@link User} of its own (the client's ID, no groups, {@link User#isServiceClient()}).
 *
 * <p>Logs the client's ID for accepted and refused tokens, never the token.
 *
 * <p>An {@link OpaqueTokenIntrospector}; the name stays from before, so that the documented logger
 * name ({@code uk.ac.ebi.tsc.tesk.config.security.ServiceClientTokenServices}) keeps working.
 */
public class ServiceClientTokenServices implements OpaqueTokenIntrospector {

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

    /** Age of a token (by iat) beyond which it is refused, whatever its exp: a year */
    private static final int MAX_TOKEN_AGE_SECONDS = 365 * 24 * 3600;

    /** How long the keys are kept if fetching them again fails, in seconds */
    private static final long RETAIN_KEYS_ON_ERROR_SECONDS = 15 * 60;

    /** The settings of the clients, by name of the property, for the messages */
    static final String CLIENTS_PROPERTY = "tesk.api.authorisation.service-clients.clients";

    /** Settings of 2.0.0, removed in 2.1.0 (issue #215) */
    static final List<String> REMOVED_PROPERTIES =
            List.of(
                    "tesk.api.authorisation.service-clients.client-ids",
                    "tesk.api.authorisation.service-clients.audience");

    /** Fields of the settings of a client */
    private static final String AUDIENCES = "audiences";

    private static final String ALLOW_ANY_AUDIENCE = "allowAnyAudience";

    /** Jackson, which refuses duplicate names and content after the value */
    private static final JsonMapper STRICT_JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    /**
     * Settings of a listed client: the audiences at which its tokens are accepted, or none with
     * {@code anyAudience}
     */
    record Client(Set<String> audiences, boolean anyAudience) {

        /** Whether one of these values of a token's {@code aud} is one of this client's */
        boolean accepts(List<String> tokenAudiences) {
            return tokenAudiences.stream().anyMatch(this.audiences::contains);
        }
    }

    private final OpaqueTokenIntrospector userInfoIntrospector;

    /** Whether clients see only their own tasks (tesk.api.authorisation.task-visibility=owner) */
    private final boolean ownerOnly;

    private final String issuer;
    private final String jwksUri;
    private final String clientClaim;

    /** The listed clients by ID */
    private final Map<String, Client> clients;

    private final boolean requireAccessTokenType;
    private final boolean requireClientSubject;
    private final int clockSkewSeconds;

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
     * @param userInfoIntrospector validates all other tokens, at the user info endpoint
     * @param properties the settings; {@code service-clients} must be enabled
     * @throws IllegalStateException if the settings lack the issuer or a client, or a client lacks
     *     audiences
     */
    public ServiceClientTokenServices(
            OpaqueTokenIntrospector userInfoIntrospector, AuthorisationProperties properties) {
        this(userInfoIntrospector, properties, JWKS_REFRESH_INTERVAL_MS);
    }

    /** As above, with the least time between two fetches of the key set (for tests) */
    ServiceClientTokenServices(
            OpaqueTokenIntrospector userInfoIntrospector,
            AuthorisationProperties properties,
            long jwksRefreshIntervalMs) {
        AuthorisationProperties.ServiceClients settings = properties.getServiceClients();
        this.ownerOnly = properties.getTaskVisibility() == TaskVisibility.OWNER;
        if (!StringUtils.hasText(settings.getIssuer())
                || !StringUtils.hasText(settings.getClientClaim())) {
            throw new IllegalStateException(
                    "tesk.api.authorisation.service-clients.enabled needs"
                            + " tesk.api.authorisation.service-clients.issuer and a client claim");
        }
        // fails at startup rather than accepting no service, or (with a blank ID) any
        Map<String, Client> listed = clients(settings.getClients());
        if (settings.getClockSkewSeconds() < 0) {
            throw new IllegalStateException(
                    "tesk.api.authorisation.service-clients.clock-skew-seconds must not be"
                            + " negative");
        }
        this.userInfoIntrospector = Objects.requireNonNull(userInfoIntrospector);
        this.issuer = settings.getIssuer().trim();
        this.jwksUri =
                StringUtils.hasText(settings.getJwksUri()) ? settings.getJwksUri().trim() : null;
        this.clientClaim = settings.getClientClaim().trim();
        this.clients = listed;
        this.requireAccessTokenType = settings.isRequireAccessTokenType();
        this.requireClientSubject = settings.isRequireClientSubject();
        this.clockSkewSeconds = settings.getClockSkewSeconds();
        this.jwksRefreshIntervalMs = jwksRefreshIntervalMs;
        if (!isSecure(this.issuer) || (this.jwksUri != null && !isSecure(this.jwksUri))) {
            throw new IllegalStateException(
                    "tesk.api.authorisation.service-clients.issuer and .jwks-uri must be https"
                            + " URLs (http only for localhost)");
        }
        warnAboutAnyAudience(listed);
    }

    /**
     * Names the clients with {@code allowAnyAudience} in a warning: their tokens are accepted
     * whatever their {@code aud}, also tokens meant for other services, so the setting is meant for
     * testing and setting up a federation, not for production
     */
    private static void warnAboutAnyAudience(Map<String, Client> listed) {
        List<String> anyAudience =
                listed.entrySet().stream()
                        .filter(entry -> entry.getValue().anyAudience())
                        .map(Map.Entry::getKey)
                        .toList();
        if (!anyAudience.isEmpty()) {
            logger.warn(
                    "Service clients whose tokens are accepted for any audience ("
                            + ALLOW_ANY_AUDIENCE
                            + ", meant for testing; list audiences in production): {}",
                    String.join(", ", anyAudience));
        }
    }

    /**
     * Refuses the settings of 2.0.0 that 2.1.0 removed (issue #215), so that an upgrade that keeps
     * them fails at startup with a hint, instead of ignoring them
     *
     * @throws IllegalStateException if one of them is set, also empty or as a list (e.g. {@code
     *     client-ids[0]} or {@code ..._CLIENT_IDS_0}), or can't be read (e.g. a list with a gap)
     */
    static void refuseRemovedSettings(Environment environment) {
        Binder binder = Binder.get(environment);
        for (String name : REMOVED_PROPERTIES) {
            boolean set;
            BindException error = null;
            try {
                set = binder.bind(name, Bindable.listOf(String.class)).isBound();
            } catch (BindException e) {
                set = true;
                error = e;
            }
            if (set) {
                throw new IllegalStateException(
                        name
                                + " was removed in 2.1.0: list the clients with their audiences in "
                                + CLIENTS_PROPERTY
                                + " (see the upgrading notes)",
                        error);
            }
        }
    }

    /**
     * The listed clients, from the JSON object of {@code clients}: client ID to {@code
     * {"audiences": [...]}} or {@code {"allowAnyAudience": true}}. IDs and audiences are trimmed.
     *
     * @throws IllegalStateException if it lists no client, or isn't such an object: e.g. a blank or
     *     duplicate client ID, a client without audiences or with a blank one, both fields, or
     *     another field
     */
    static Map<String, Client> clients(String json) {
        if (!StringUtils.hasText(json)) {
            // also for the removed settings in forms that refuseRemovedSettings doesn't see
            throw invalidClients(
                    "not set (client-ids and audience were removed in 2.1.0, see the upgrading"
                            + " notes)");
        }
        JsonNode root;
        try {
            root = STRICT_JSON.readTree(json);
        } catch (JacksonException e) {
            // the message of the parser, which may quote the settings, but not a token
            throw invalidClients("not valid JSON (" + e.getOriginalMessage() + ")");
        }
        if (!root.isObject()) {
            throw invalidClients("not a JSON object");
        }
        Map<String, Client> listed = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            String id = entry.getKey().strip();
            if (id.isEmpty()) {
                throw invalidClients("a blank client ID");
            }
            if (listed.put(id, client(id, entry.getValue())) != null) {
                throw invalidClients("client " + id + " twice");
            }
        }
        if (listed.isEmpty()) {
            throw invalidClients("no client");
        }
        return Collections.unmodifiableMap(listed);
    }

    private static Client client(String id, JsonNode settings) {
        if (!settings.isObject()) {
            throw invalidClients("client " + id + ": not a JSON object");
        }
        for (String field : settings.propertyNames()) {
            if (!AUDIENCES.equals(field) && !ALLOW_ANY_AUDIENCE.equals(field)) {
                throw invalidClients(
                        "client "
                                + id
                                + ": unknown field "
                                + field
                                + " (only "
                                + AUDIENCES
                                + " and "
                                + ALLOW_ANY_AUDIENCE
                                + ")");
            }
        }
        JsonNode any = settings.get(ALLOW_ANY_AUDIENCE);
        if (any != null && !any.isBoolean()) {
            throw invalidClients("client " + id + ": " + ALLOW_ANY_AUDIENCE + " not true or false");
        }
        boolean anyAudience = any != null && any.booleanValue();
        JsonNode audiences = settings.get(AUDIENCES);
        if (anyAudience) {
            if (audiences != null) {
                throw invalidClients(
                        "client " + id + ": " + AUDIENCES + " and " + ALLOW_ANY_AUDIENCE + " both");
            }
            return new Client(Collections.emptySet(), true);
        }
        if (audiences == null || !audiences.isArray() || audiences.isEmpty()) {
            throw invalidClients(
                    "client "
                            + id
                            + ": no "
                            + AUDIENCES
                            + " (a non-empty list, or "
                            + ALLOW_ANY_AUDIENCE
                            + " true)");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode audience : audiences) {
            if (!audience.isString() || audience.stringValue().isBlank()) {
                throw invalidClients(
                        "client " + id + ": an audience that isn't a non-blank string");
            }
            values.add(audience.stringValue().strip());
        }
        return new Client(Collections.unmodifiableSet(values), false);
    }

    private static IllegalStateException invalidClients(String problem) {
        return new IllegalStateException(
                "tesk.api.authorisation.service-clients.enabled needs "
                        + CLIENTS_PROPERTY
                        + ": a JSON object from client ID to {\""
                        + AUDIENCES
                        + "\": [...]} or {\""
                        + ALLOW_ANY_AUDIENCE
                        + "\": true}; "
                        + problem);
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

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String accessToken) {
        String clientId = listedClient(accessToken);
        if (clientId == null) {
            OAuth2AuthenticatedPrincipal principal =
                    this.userInfoIntrospector.introspect(accessToken);
            if (this.clients.containsKey(principal.getName())) {
                // a user with a listed client's ID would share the client's tasks
                logger.warn(
                        "Refused user token: its sub is the ID of service client {}",
                        principal.getName());
                throw new BadOpaqueTokenException("User ID of a service client");
            }
            return principal;
        }
        JwtClaims claims;
        try {
            claims = verifier().process(accessToken).getJwtClaims();
        } catch (InvalidJwtException e) {
            logger.warn("Refused token of service client {}: {}", clientId, reasons(e));
            throw new BadOpaqueTokenException("Invalid access token of a service client");
        } catch (IOException | JoseException e) {
            // the discovery document can't be read or has no JWKS URI
            logger.warn(
                    "Refused token of service client {}: keys of the issuer unavailable ({})",
                    clientId,
                    e.getMessage());
            throw new BadOpaqueTokenException("Invalid access token of a service client");
        } catch (RuntimeException e) {
            // unexpected: logged with its stack trace (which doesn't hold the token)
            logger.warn("Refused token of service client {}: unexpected error", clientId, e);
            throw new BadOpaqueTokenException("Invalid access token of a service client");
        }
        if (!hasAcceptedAudience(clientId, claims)) {
            logger.warn("Refused token of service client {}: wrong audience", clientId);
            throw new BadOpaqueTokenException("Invalid access token of a service client");
        }
        logger.info("Accepted token of service client {}", clientId);
        return principal(clientId, claims, this.ownerOnly);
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
                || !this.clients.containsKey(client)) {
            return null;
        }
        if (this.requireClientSubject && !client.equals(claims.getClaimValue("sub"))) {
            return null; // a user's token, obtained through the client
        }
        return (String) client;
    }

    /**
     * Whether the token's {@code aud} (a string or a list of strings) is or contains one of the
     * client's audiences, or the client may have any
     */
    private boolean hasAcceptedAudience(String clientId, JwtClaims claims) {
        Client client = this.clients.get(clientId);
        if (client.anyAudience()) {
            return true;
        }
        try {
            return claims.hasAudience() && client.accepts(claims.getAudience());
        } catch (MalformedClaimException e) {
            return false; // aud neither a string nor a list of strings
        }
    }

    /** The client, as a principal of its own without groups, with the claims of its token */
    private static OAuth2AuthenticatedPrincipal principal(
            String clientId, JwtClaims claims, boolean ownerOnly) {
        User user =
                User.builder(clientId)
                        .allGroups(Collections.emptySet())
                        .serviceClient(true)
                        .legacyUserLabels(false)
                        .ownerOnly(ownerOnly)
                        .build();
        return new AuthenticatedUser(user, claims.getClaimsMap(), AuthorityUtils.NO_AUTHORITIES);
    }

    /**
     * The verifier, built on first use. If the discovery fails, it isn't tried again for the
     * refresh interval: tokens (which may be forged, as the claims that select this path are read
     * before the signature is checked) are refused at once until then, so that they can't make
     * requests wait for the issuer one after another. While one request builds it, the others are
     * refused at once too. A request that found no verifier just before another one finished
     * building it uses that one.
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
            consumer = this.verifier;
            if (consumer != null) {
                return consumer;
            }
            if (System.currentTimeMillis() < this.discoveryRetryAt) {
                throw new IOException("fetching the keys failed recently");
            }
            try {
                consumer = buildVerifier(keyResolver());
            } catch (IOException | JoseException | RuntimeException e) {
                this.discoveryRetryAt = System.currentTimeMillis() + this.jwksRefreshIntervalMs;
                throw e;
            }
            this.verifier = consumer;
            return consumer;
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
        // throws an IOException unless the status is 200
        SimpleResponse response = httpGet().get(url);
        Map<String, Object> document = JsonUtil.parseJson(response.getBody());
        Object jwks = document.get("jwks_uri");
        if (!this.issuer.equals(document.get("issuer")) || !(jwks instanceof String)) {
            throw new JoseException("Discovery document of another issuer or without jwks_uri");
        }
        if (!isSecure((String) jwks)) {
            throw new JoseException("Discovery document with a jwks_uri that isn't https");
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
                        // iat is required by RFC 9068, and may be neither in the future nor more
                        // than a year old
                        .setRequireIssuedAt()
                        .setIssuedAtRestrictions(this.clockSkewSeconds, MAX_TOKEN_AGE_SECONDS)
                        .setAllowedClockSkewInSeconds(this.clockSkewSeconds);
        // the audiences differ per client: checked after the signature, in introspect
        builder.setSkipDefaultAudienceValidation();
        if (this.requireAccessTokenType) {
            builder.setExpectedType(true, "at+jwt");
        }
        return builder.build();
    }

    /** Why a token was refused, without the token or its claims */
    static String reasons(InvalidJwtException e) {
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
