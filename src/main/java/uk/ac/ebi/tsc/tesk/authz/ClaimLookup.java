package uk.ac.ebi.tsc.tesk.authz;

import java.util.Map;
import java.util.Optional;

/**
 * Looks a claim up by name in the claims of the caller (the user info, or a service client's
 * token): the exact top-level name first (so that URI-named claims such as {@code
 * https://example.org/groups} are found), else a dotted path through nested objects. Used by the
 * required claim ({@code tesk.api.authz.required-claim}) and the claims sent to the decision
 * service ({@code tesk.api.authz.decision-service.claims}); not by the owner of tasks, which stays
 * the {@code sub}.
 */
public final class ClaimLookup {

    private ClaimLookup() {}

    /**
     * The value of the claim, or empty if there is none (a JSON {@code null} counts as none)
     *
     * @param claims the caller's claims
     * @param name the claim's name, or a dotted path
     */
    public static Optional<Object> lookup(Map<String, Object> claims, String name) {
        if (claims == null || name == null) {
            return Optional.empty();
        }
        if (claims.containsKey(name)) {
            return Optional.ofNullable(claims.get(name));
        }
        Object node = claims;
        for (String part : name.split("\\.", -1)) {
            if (!(node instanceof Map) || !((Map<?, ?>) node).containsKey(part)) {
                return Optional.empty();
            }
            node = ((Map<?, ?>) node).get(part);
        }
        return Optional.ofNullable(node);
    }
}
