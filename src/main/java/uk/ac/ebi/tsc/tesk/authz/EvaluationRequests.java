package uk.ac.ebi.tsc.tesk.authz;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenPrincipal;

/**
 * Builds the evaluation requests of profile {@code tes-authzen/3} of tes-authz (spec sections 3.1,
 * 3.4 and 3.7): the caller ({@code subject}), the operation ({@code action}), the resource, and the
 * context ({@code tes.profile}, {@code tes.service_id}). Never the access token.
 */
class EvaluationRequests {

    /** The profile of the requests (the tes-authz spec) */
    static final String PROFILE = "tes-authzen/3";

    private final List<String> claims;
    private final Supplier<String> serviceId;

    /**
     * @param claims the names of the claims that the decision service gets
     * @param serviceId the ID of the service (service info), for the request's context
     */
    EvaluationRequests(List<String> claims, Supplier<String> serviceId) {
        this.claims = List.copyOf(claims);
        this.serviceId = Objects.requireNonNull(serviceId);
    }

    /** The names of the claims that the decision service gets */
    List<String> claims() {
        return this.claims;
    }

    /**
     * An evaluation request (spec section 3.7)
     *
     * @param subject the caller ({@link #subject})
     * @param action the operation's {@code operationId}, e.g. {@code GetTask}
     * @param actionProperties the action's properties ({@code {}} if none)
     * @param resource the resource
     */
    Map<String, Object> request(
            Map<String, Object> subject,
            String action,
            Map<String, Object> actionProperties,
            Map<String, Object> resource) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        Map<String, Object> actionObject = new LinkedHashMap<>();
        actionObject.put("name", action);
        actionObject.put("properties", actionProperties);
        body.put("action", actionObject);
        body.put("resource", resource);
        Map<String, Object> tes = new LinkedHashMap<>();
        tes.put("profile", PROFILE);
        String id = this.serviceId.get();
        if (id != null) {
            tes.put("service_id", id);
        }
        body.put("context", Map.of("tes", tes));
        return body;
    }

    /**
     * The subject (spec section 3.1): always the caller, identified by its owner ID; keys without a
     * value are left out, {@code claims} is always there
     *
     * @param principal the caller's validated token
     * @param userInfoClaims the caller's user info ({@code {}} if it wasn't looked up)
     */
    Map<String, Object> subject(TokenPrincipal principal, Map<String, Object> userInfoClaims) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("iss", principal.issuer());
        properties.put("sub", principal.subject());
        if (principal.clientId() != null) {
            properties.put("client_id", principal.clientId());
        }
        scope(principal.claims().get("scope")).ifPresent(scope -> properties.put("scope", scope));
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String name : this.claims) {
            ClaimLookup.lookup(principal.claims(), userInfoClaims, name)
                    .ifPresent(value -> resolved.put(name, value));
        }
        properties.put("claims", resolved);
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("type", "user");
        subject.put("id", principal.owner());
        subject.put("properties", properties);
        return subject;
    }

    /** The scope as a list: from a space-separated string (RFC 9068) or a list of strings */
    static Optional<List<String>> scope(Object value) {
        if (value instanceof String string) {
            List<String> scopes =
                    Arrays.stream(string.split(" ")).filter(scope -> !scope.isEmpty()).toList();
            return Optional.of(scopes);
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return Optional.of(list.stream().map(String.class::cast).toList());
        }
        return Optional.empty();
    }
}
