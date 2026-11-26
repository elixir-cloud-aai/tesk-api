package uk.ac.ebi.tsc.tesk.config.security.token;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The caller of a request with a valid token: a user, or a listed service client. The caller is
 * identified by issuer and subject together ({@link #owner()}), since {@code sub} is only unique
 * per issuer.
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

    /**
     * Checks that the fields are set, and copies the claims, nested objects and arrays too, into
     * ones that can't be changed (they may hold JSON nulls)
     */
    public TokenPrincipal {
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(subject);
        Objects.requireNonNull(kind);
        claims = copy(claims);
    }

    private static Map<String, Object> copy(Map<?, ?> map) {
        Map<String, Object> copied = new LinkedHashMap<>();
        map.forEach((key, value) -> copied.put(String.valueOf(key), copyValue(value)));
        return Collections.unmodifiableMap(copied);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copy(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            list.forEach(item -> copied.add(copyValue(item)));
            return Collections.unmodifiableList(copied);
        }
        return value; // strings, numbers, booleans, null
    }

    /** Without the claims, which may hold personal data (names, e-mail addresses) */
    @Override
    public String toString() {
        return "TokenPrincipal[issuer="
                + this.issuer
                + ", subject="
                + this.subject
                + ", kind="
                + this.kind
                + ", clientId="
                + this.clientId
                + "]";
    }

    /** The identity of the caller as owner of tasks: {@code <iss>|<sub>} */
    public String owner() {
        return this.issuer + OWNER_SEPARATOR + this.subject;
    }
}
