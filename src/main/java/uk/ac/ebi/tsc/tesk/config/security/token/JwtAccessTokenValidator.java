package uk.ac.ebi.tsc.tesk.config.security.token;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.ErrorCodeValidator;
import org.jose4j.jwt.consumer.ErrorCodes;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.keys.resolvers.VerificationKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Validates JWT access tokens (RFC 9068) locally, against the keys of the configured issuers
 * ({@link TokenValidationProperties}):
 *
 * <ol>
 *   <li>a single signed JWT (no opaque, encrypted or nested tokens);
 *   <li>{@code iss} exactly one of the configured issuers;
 *   <li>signature against that issuer's keys ({@link IssuerVerifier}), with one of the configured
 *       asymmetric algorithms ({@code none} and HMAC are refused at startup);
 *   <li>header {@code typ} {@code at+jwt} (unless switched off);
 *   <li>{@code exp} required, {@code nbf}, and {@code iat} required, not in the future and not
 *       older than the maximum age, all with the clock skew;
 *   <li>{@code sub} present; no {@code act} (delegated tokens, RFC 8693, are refused until TESK API
 *       supports actors);
 *   <li>a listed service client (client claim and {@code sub} both its ID) needs its own ID in
 *       {@code aud} (the issuer puts no TESK audience into client tokens; a token for any other
 *       audience was meant for another service); any other token is a user's and needs one of the
 *       issuer's audiences (the client IDs of the front ends through which users call TESK API) in
 *       {@code aud}; a user whose {@code sub} is a listed client's ID is refused (they would share
 *       its tasks).
 * </ol>
 *
 * <p>Logs accepted service clients by ID, refusals by reason, never the token.
 */
public final class JwtAccessTokenValidator implements TokenValidator {

    private static final Logger logger = LoggerFactory.getLogger(JwtAccessTokenValidator.class);

    /** The algorithms that may be configured: asymmetric ones only */
    static final Set<String> ASYMMETRIC_ALGORITHMS =
            Set.of(
                    AlgorithmIdentifiers.RSA_USING_SHA256,
                    AlgorithmIdentifiers.RSA_USING_SHA384,
                    AlgorithmIdentifiers.RSA_USING_SHA512,
                    AlgorithmIdentifiers.RSA_PSS_USING_SHA256,
                    AlgorithmIdentifiers.RSA_PSS_USING_SHA384,
                    AlgorithmIdentifiers.RSA_PSS_USING_SHA512,
                    AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256,
                    AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384,
                    AlgorithmIdentifiers.ECDSA_USING_P521_CURVE_AND_SHA512);

    private static final String PREFIX = "tesk.api.auth.";

    /** Upper bound of the timeouts of the requests to the issuers, in seconds */
    static final int MAX_TIMEOUT_SECONDS = 300;

    static final String DELEGATED = "delegated token (act) not accepted";
    static final String WRONG_AUDIENCE = "wrong audience";
    static final String NO_SUBJECT = "no sub";

    /** Settings and verifier of an accepted issuer */
    private record Issuer(
            String url,
            IssuerVerifier verifier,
            Set<String> audiences,
            String clientClaim,
            Set<String> serviceClients) {}

    /** The accepted issuers by URL */
    private final Map<String, Issuer> issuers;

    /** Parses tokens without checking them, to find their issuer */
    private final JwtConsumer parser =
            new JwtConsumerBuilder()
                    .setSkipAllValidators()
                    .setDisableRequireSignature()
                    .setSkipSignatureVerification()
                    .build();

    /**
     * @param properties the settings
     * @throws IllegalStateException if they are incomplete or invalid (e.g. no issuer, an issuer
     *     with neither audiences nor service clients, a plain http URL, a symmetric algorithm)
     */
    public JwtAccessTokenValidator(TokenValidationProperties properties) {
        this(properties, IssuerVerifier.REFRESH_INTERVAL_MS);
    }

    /** As above, with the least time between two fetches of the keys (for tests) */
    JwtAccessTokenValidator(TokenValidationProperties properties, long refreshIntervalMs) {
        AlgorithmConstraints algorithms = algorithms(properties.getAlgorithms());
        check(properties.getClockSkewSeconds() >= 0, "clock-skew-seconds must not be negative");
        check(properties.getMaxTokenAgeSeconds() > 0, "max-token-age-seconds must be positive");
        check(
                properties.getTimeoutSeconds() > 0
                        && properties.getTimeoutSeconds() <= MAX_TIMEOUT_SECONDS,
                "timeout-seconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        List<TokenValidationProperties.Issuer> settings = properties.getIssuers();
        check(!settings.isEmpty(), "issuers must list at least one issuer");
        Map<String, Issuer> byUrl = new LinkedHashMap<>();
        for (int i = 0; i < settings.size(); i++) {
            Issuer issuer =
                    issuer(
                            "issuers[" + i + "].",
                            settings.get(i),
                            properties,
                            algorithms,
                            refreshIntervalMs);
            check(
                    byUrl.put(issuer.url(), issuer) == null,
                    "issuers lists " + issuer.url() + " twice");
        }
        this.issuers = Collections.unmodifiableMap(byUrl);
    }

    private static void check(boolean valid, String problem) {
        if (!valid) {
            throw new IllegalStateException(PREFIX + problem);
        }
    }

    private static AlgorithmConstraints algorithms(List<String> names) {
        check(!names.isEmpty(), "algorithms must list at least one algorithm");
        for (String name : names) {
            check(
                    ASYMMETRIC_ALGORITHMS.contains(name),
                    "algorithms: "
                            + name
                            + " is not an accepted asymmetric algorithm (one of "
                            + String.join(", ", ASYMMETRIC_ALGORITHMS.stream().sorted().toList())
                            + ")");
        }
        return new AlgorithmConstraints(
                AlgorithmConstraints.ConstraintType.PERMIT, names.toArray(new String[0]));
    }

    private static Issuer issuer(
            String key,
            TokenValidationProperties.Issuer settings,
            TokenValidationProperties properties,
            AlgorithmConstraints algorithms,
            long refreshIntervalMs) {
        String url = trimmed(settings.getUrl());
        check(url != null, key + "url must be set");
        // also refuses the separator of the owner (TokenPrincipal), which URLs can't hold
        check(
                IssuerVerifier.isSecure(url),
                key + "url must be an https URL (http only for localhost)");
        String jwksUri = trimmed(settings.getJwksUri());
        check(
                jwksUri == null || IssuerVerifier.isSecure(jwksUri),
                key + "jwks-uri must be an https URL (http only for localhost)");
        String clientClaim = trimmed(settings.getClientClaim());
        check(clientClaim != null, key + "client-claim must be set");
        Set<String> audiences = new LinkedHashSet<>();
        for (String audience : settings.getAudiences()) {
            String value = trimmed(audience);
            check(value != null, key + "audiences must not have blank values");
            audiences.add(value);
        }
        Set<String> clients = new LinkedHashSet<>();
        List<TokenValidationProperties.ServiceClient> listed = settings.getServiceClients();
        for (int j = 0; j < listed.size(); j++) {
            String id = trimmed(listed.get(j).getClientId());
            check(id != null, key + "service-clients[" + j + "].client-id must be set");
            check(clients.add(id), key + "service-clients lists " + id + " twice");
        }
        check(
                !audiences.isEmpty() || !clients.isEmpty(),
                key + "audiences or " + key + "service-clients must list at least one value");
        IssuerVerifier verifier =
                new IssuerVerifier(
                        url,
                        jwksUri,
                        properties.getTimeoutSeconds() * 1000,
                        refreshIntervalMs,
                        keys -> consumer(url, keys, algorithms, properties));
        return new Issuer(
                url,
                verifier,
                Collections.unmodifiableSet(audiences),
                clientClaim,
                Collections.unmodifiableSet(clients));
    }

    /** The value without surrounding white space, or null if blank */
    private static String trimmed(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    /** Checks all but the subject, the audience and the classification (in {@link #principal}) */
    private static JwtConsumer consumer(
            String url,
            VerificationKeyResolver keys,
            AlgorithmConstraints algorithms,
            TokenValidationProperties properties) {
        JwtConsumerBuilder builder =
                new JwtConsumerBuilder()
                        .setVerificationKeyResolver(keys)
                        .setJwsAlgorithmConstraints(algorithms)
                        .setExpectedIssuer(true, url)
                        .setRequireExpirationTime()
                        .setRequireIssuedAt()
                        .setRequireSubject()
                        // jose4j adds the clock skew to both bounds: iat at most the skew in the
                        // future, and at most the maximum age (plus the skew) in the past
                        .setIssuedAtRestrictions(0, properties.getMaxTokenAgeSeconds())
                        .setAllowedClockSkewInSeconds(properties.getClockSkewSeconds())
                        // the accepted audiences depend on the kind of caller
                        .setSkipDefaultAudienceValidation();
        if (properties.isRequireAccessTokenType()) {
            builder.setExpectedType(true, "at+jwt");
        }
        return builder.build();
    }

    @Override
    public TokenPrincipal validate(String token) {
        if (!StringUtils.hasText(token)) {
            throw refused("no token");
        }
        Issuer issuer = this.issuers.get(unverifiedIssuer(token));
        if (issuer == null) {
            throw refused("unknown issuer");
        }
        JwtClaims claims;
        try {
            claims = issuer.verifier().verify(token);
        } catch (InvalidJwtException e) {
            throw refused(reasons(e));
        } catch (IOException e) {
            logger.warn("Keys of issuer {} unavailable: {}", issuer.url(), e.getMessage());
            throw new IssuerUnavailableException("keys of the issuer unavailable");
        }
        return principal(issuer, claims);
    }

    /** The {@code iss} of a single signed JWT, unverified, or null */
    private String unverifiedIssuer(String token) {
        JwtContext context;
        try {
            context = this.parser.process(token);
        } catch (InvalidJwtException | RuntimeException e) {
            throw refused("not a signed JWT");
        }
        // encrypted tokens already fail above (no decryption key); nested ones have two objects
        if (context.getJoseObjects().size() != 1) {
            throw refused("not a signed JWT");
        }
        Object iss = context.getJwtClaims().getClaimValue("iss");
        return iss instanceof String ? (String) iss : null;
    }

    private static TokenPrincipal principal(Issuer issuer, JwtClaims claims) {
        // a string: the consumer requires it, and refuses other types
        String subject = (String) claims.getClaimValue("sub");
        if (!StringUtils.hasText(subject)) {
            throw refused(NO_SUBJECT);
        }
        if (claims.hasClaim("act")) {
            throw refused(DELEGATED);
        }
        Object client = claims.getClaimValue(issuer.clientClaim());
        String clientId = client instanceof String ? (String) client : null;
        List<String> audiences;
        try {
            audiences = claims.hasAudience() ? claims.getAudience() : List.of();
        } catch (MalformedClaimException e) {
            throw refused(WRONG_AUDIENCE);
        }
        TokenPrincipal.Kind kind;
        if (subject.equals(clientId) && issuer.serviceClients().contains(subject)) {
            // a listed client's own token is meant for TESK only with its own ID as audience
            if (!audiences.contains(subject)) {
                throw refused(WRONG_AUDIENCE);
            }
            kind = TokenPrincipal.Kind.SERVICE_CLIENT;
            logger.debug("Accepted token of service client {}", subject);
        } else {
            if (issuer.serviceClients().contains(subject)) {
                throw refused("user with the ID of a service client");
            }
            if (audiences.stream().noneMatch(issuer.audiences()::contains)) {
                throw refused(WRONG_AUDIENCE);
            }
            kind = TokenPrincipal.Kind.USER;
        }
        return new TokenPrincipal(issuer.url(), subject, kind, clientId, claims.getClaimsMap());
    }

    private static InvalidTokenException refused(String reason) {
        // not WARN: callers without a valid token decide how often this is logged
        logger.info("Refused token: {}", reason);
        return new InvalidTokenException(reason);
    }

    /** Why the token was refused, without the token or its claims */
    static String reasons(InvalidJwtException e) {
        List<String> reasons =
                e.getErrorDetails().stream()
                        .map(ErrorCodeValidator.Error::getErrorCode)
                        .map(JwtAccessTokenValidator::reason)
                        .distinct()
                        .toList();
        return reasons.isEmpty() ? "invalid token" : String.join(", ", reasons);
    }

    private static String reason(int code) {
        return switch (code) {
            case ErrorCodes.EXPIRED -> "expired";
            case ErrorCodes.NOT_YET_VALID -> "not yet valid";
            case ErrorCodes.EXPIRATION_MISSING -> "no expiration time";
            case ErrorCodes.ISSUED_AT_MISSING,
                    ErrorCodes.ISSUED_AT_INVALID_FUTURE,
                    ErrorCodes.ISSUED_AT_INVALID_PAST ->
                    "issued at (iat) missing or out of range";
            case ErrorCodes.SUBJECT_MISSING -> NO_SUBJECT;
            case ErrorCodes.MALFORMED_CLAIM -> "malformed claim";
            case ErrorCodes.SIGNATURE_INVALID -> "invalid signature";
            case ErrorCodes.TYPE_INVALID, ErrorCodes.TYPE_MISSING -> "not an access token (typ)";
            default -> "invalid token (code " + code + ")";
        };
    }
}
