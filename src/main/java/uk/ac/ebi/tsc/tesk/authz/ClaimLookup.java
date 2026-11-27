package uk.ac.ebi.tsc.tesk.authz;

import java.util.Map;
import java.util.Optional;

/**
 * Looks a claim up by name, as the tes-authz spec does ({@code lookup}, section 6): in the access
 * token's claims and the user info, first by the exact top-level name (so that URI-named claims
 * such as {@code https://example.org/groups} are found), token before user info, then as a dotted
 * path through nested objects, token before user info. A JSON {@code null} counts as missing.
 */
public final class ClaimLookup {

    private ClaimLookup() {}

    /**
     * Whether the claims hold the name exactly (not as a path), with a value
     *
     * @param claims the claims
     * @param name the claim's name
     */
    public static boolean hasExact(Map<String, Object> claims, String name) {
        return claims.get(name) != null;
    }

    /**
     * The value of the claim, or empty if there is none
     *
     * @param token the access token's claims
     * @param userInfo the user info's claims ({@code {}} if it wasn't looked up)
     * @param name the claim's name, or a dotted path
     */
    public static Optional<Object> lookup(
            Map<String, Object> token, Map<String, Object> userInfo, String name) {
        if (hasExact(token, name)) {
            return Optional.of(token.get(name));
        }
        if (hasExact(userInfo, name)) {
            return Optional.of(userInfo.get(name));
        }
        Optional<Object> path = path(token, name);
        return path.isPresent() ? path : path(userInfo, name);
    }

    /** The value at the dotted path, or empty */
    static Optional<Object> path(Map<String, Object> claims, String name) {
        Object node = claims;
        for (String part : name.split("\\.", -1)) {
            if (!(node instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return Optional.empty();
            }
            node = map.get(part);
        }
        return Optional.ofNullable(node);
    }
}
