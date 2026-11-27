package uk.ac.ebi.tsc.tesk.authz;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.config.security.token.InvalidTokenException;
import uk.ac.ebi.tsc.tesk.config.security.token.IssuerUnavailableException;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenPrincipal;
import uk.ac.ebi.tsc.tesk.config.security.token.UserInfoClient;
import uk.ac.ebi.tsc.tesk.tes.exception.AuthorisationUnavailableException;
import uk.ac.ebi.tsc.tesk.tes.exception.UserInfoRefusedException;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * Decides whether the caller may create a task: by asking the decision service once (OpenID AuthZEN
 * 1.0 Access Evaluation, profile {@code tes-authzen/3} of tes-authz, sections 3 and 6), and doing
 * what it answers. Called by Spring Security before {@code TesService#createTask} ({@code
 * @createAuthorisation}); get, list and cancel are TESK API's own decisions (owner only).
 *
 * <p>The request names the caller ({@code subject}: the owner ID, issuer, subject, client, scope
 * and the claims of {@code tesk.api.authz.decision-service.claims}), the operation ({@code
 * CreateTask}) and the caller's set of tasks ({@code tes_task_set} {@code own}) with the task's
 * owner and a fixed projection of the task ({@link TaskProjection}). Never the access token.
 *
 * <p>The claims come from the access token, else from the user info endpoint of its issuer ({@link
 * ClaimLookup}), which is asked only if a listed claim isn't in the token, and never for the tokens
 * of clients (client credentials grant: {@code sub} is the token's client), which no user info
 * endpoint answers.
 *
 * <ul>
 *   <li>allowed ({@code "decision": true}): the task is created;
 *   <li>denied (any other decision): {@code 403};
 *   <li>the decision service fails (timeout, connection error, a status other than 200, no JSON, no
 *       boolean decision), or the user info can't be looked up: {@code 503};
 *   <li>the user info endpoint refuses the token (401, 403) or answers for another subject: {@code
 *       401}.
 * </ul>
 */
public class CreateAuthorisation {

    private static final Logger logger = LoggerFactory.getLogger(CreateAuthorisation.class);

    /** The profile of the requests (the tes-authz spec) */
    static final String PROFILE = "tes-authzen/3";

    private final DecisionServiceClient decisionService;
    private final UserInfoClient userInfo;
    private final List<String> claims;
    private final Supplier<String> serviceId;
    private final Supplier<String> accessToken;

    /**
     * @param decisionService the client of the decision service
     * @param userInfo the client of the issuers' user info endpoints
     * @param claims the names of the claims that the decision service gets
     * @param serviceId the ID of the service (service info), for the request's context
     * @param accessToken the access token of the current request
     */
    public CreateAuthorisation(
            DecisionServiceClient decisionService,
            UserInfoClient userInfo,
            List<String> claims,
            Supplier<String> serviceId,
            Supplier<String> accessToken) {
        this.decisionService = Objects.requireNonNull(decisionService);
        this.userInfo = Objects.requireNonNull(userInfo);
        this.claims = List.copyOf(claims);
        this.serviceId = Objects.requireNonNull(serviceId);
        this.accessToken = Objects.requireNonNull(accessToken);
    }

    /**
     * Whether the caller may create the task
     *
     * @param task the submitted task
     * @param user the caller
     * @return the decision service's decision
     * @throws AuthorisationUnavailableException if it can't be decided now ({@code 503})
     * @throws UserInfoRefusedException if the user info endpoint refuses the token ({@code 401})
     */
    public boolean mayCreate(TesTask task, User user) {
        Optional<TokenPrincipal> found = user.getPrincipal();
        if (found.isEmpty()) {
            // not expected: with authentication, every caller has a validated token
            logger.warn("Create denied: the caller has no validated token");
            return false;
        }
        TokenPrincipal principal = found.get();
        Map<String, Object> userInfoClaims =
                needsUserInfo(principal) ? lookUp(principal) : Map.of();
        Map<String, Object> request = request(principal, userInfoClaims, task);
        boolean allowed;
        try {
            allowed = this.decisionService.evaluate(request, UUID.randomUUID().toString());
        } catch (DecisionServiceClient.Failure e) {
            logger.warn(
                    "Decision service failed on CreateTask of {}: {}",
                    principal.owner(),
                    e.getMessage());
            throw new AuthorisationUnavailableException();
        }
        if (!allowed) {
            logger.info("Decision service denied CreateTask of {}", principal.owner());
        }
        return allowed;
    }

    /**
     * Whether the user info is needed: a listed claim isn't in the token by its exact name, and the
     * caller isn't a client with a token of its own
     */
    boolean needsUserInfo(TokenPrincipal principal) {
        return !isClientToken(principal)
                && this.claims.stream()
                        .anyMatch(name -> !ClaimLookup.hasExact(principal.claims(), name));
    }

    /**
     * Whether the token is a client's own (client credentials grant): a listed service client, or
     * any token whose {@code sub} is its client
     */
    static boolean isClientToken(TokenPrincipal principal) {
        return principal.kind() == TokenPrincipal.Kind.SERVICE_CLIENT
                || principal.subject().equals(principal.clientId());
    }

    private Map<String, Object> lookUp(TokenPrincipal principal) {
        String token = this.accessToken.get();
        if (token == null) {
            // not expected: the caller was authenticated with this request's token
            logger.warn("Create of {}: no access token for the user info", principal.owner());
            throw new AuthorisationUnavailableException();
        }
        try {
            return this.userInfo.claims(principal, token);
        } catch (InvalidTokenException e) {
            throw new UserInfoRefusedException();
        } catch (IssuerUnavailableException e) {
            throw new AuthorisationUnavailableException();
        }
    }

    /** The evaluation request of CreateTask (spec section 3.7) */
    Map<String, Object> request(
            TokenPrincipal principal, Map<String, Object> userInfoClaims, TesTask task) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject(principal, userInfoClaims));
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("name", "CreateTask");
        action.put("properties", Map.of());
        body.put("action", action);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("owner", principal.owner());
        properties.put("task", TaskProjection.of(task));
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", "tes_task_set");
        resource.put("id", "own");
        resource.put("properties", properties);
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

    /** The bearer token of the current request (from the {@code Authorization} header), or null */
    static String currentAccessToken() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return null;
        }
        HttpServletRequest request = servlet.getRequest();
        return new DefaultBearerTokenResolver().resolve(request);
    }
}
