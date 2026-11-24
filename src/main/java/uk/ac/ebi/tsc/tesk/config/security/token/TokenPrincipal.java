package uk.ac.ebi.tsc.tesk.config.security.token;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The caller of a request with a valid token: a user, or a listed service client (D-392). The
 * caller is identified by issuer and subject together ({@link #owner()}), since {@code sub} is only
 * unique per issuer.
 *
 * @param issuer the {@code iss} of the token, exactly as configured
 * @param subject the {@code sub} of the token
 * @param kind user or service client
 * @param clientId the client the token was issued to (the client claim, by default {@code
 *     client_id}), or null if the token names none
 * @param claims all claims of the token (e.g. for the entitlements, if the issuer puts them into
 *     the token)
 */
public record TokenPrincipal(
        String issuer, String subject, Kind kind, String clientId, Map<String, Object> claims) {

    /** Separates issuer and subject in {@link #owner()}; issuer URLs can't hold it */
    public static final String OWNER_SEPARATOR = "|";

    /** Who the caller is */
    public enum Kind {
        /** A user, with a token obtained through one of the accepted clients */
        USER,
        /** A listed service client, with a token of its own (client credentials grant) */
        SERVICE_CLIENT
    }

    /** Checks that the fields are set, and copies the claims (which may hold JSON nulls) */
    public TokenPrincipal {
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(subject);
        Objects.requireNonNull(kind);
        claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
    }

    /** The identity of the caller as owner of tasks: {@code <iss>|<sub>} */
    public String owner() {
        return this.issuer + OWNER_SEPARATOR + this.subject;
    }
}
