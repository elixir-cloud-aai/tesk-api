package uk.ac.ebi.tsc.tesk.authz;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.tes.exception.AuthorisationUnavailableException;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * The authorisation hook of the profile {@code tes-authzen/1} (tesk.api.authz), as the bean {@code
 * tesAuthz}: the required claim, an AND gate before all other rules ({@link #gate}), and, if a
 * decision service is set ({@link #isDecisionService}), one OpenID AuthZEN 1.0 Access Evaluation
 * per decision instead of TESK's group model. Without settings, nothing changes.
 *
 * <p>The decision service gets the caller's identity (owner ID, {@code iss}, {@code sub}, and for
 * service clients {@code client_id} and {@code scope}; plus the allow-listed claims), never the
 * access token. Users' tokens are validated at the user info endpoint, so their claims come from
 * the user info only, and they have no {@code client_id} or {@code scope}.
 *
 * <p>Any failure of the decision service denies the request with 503.
 */
public class TesAuthz {

    private static final Logger logger = LoggerFactory.getLogger(TesAuthz.class);

    /** The version of the vocabulary sent to the decision service */
    public static final String PROFILE = "tes-authzen/1";

    /** Separator of issuer and subject in owner IDs */
    static final String OWNER_SEPARATOR = "|";

    private final AuthzSettings settings;
    private final DecisionServiceClient client;
    private final Supplier<String> serviceId;
    private final String serviceClientIssuer;

    /**
     * Checks the settings, refusing to start (IllegalStateException) on unsafe ones
     *
     * @param settings the settings
     * @param authEnabled whether tokens are validated (profile auth)
     * @param serviceId the ID of the service info, as sent to the decision service
     * @param serviceClientIssuer the issuer of service clients' tokens, if they are enabled, or
     *     null: tasks created before owners were stamped get an owner only if no other issuer can
     *     have created them
     */
    public TesAuthz(
            AuthzSettings settings,
            boolean authEnabled,
            Supplier<String> serviceId,
            String serviceClientIssuer) {
        this(settings, authEnabled, serviceId, serviceClientIssuer, null);
    }

    /** As above, with the client of the decision service (for tests) */
    TesAuthz(
            AuthzSettings settings,
            boolean authEnabled,
            Supplier<String> serviceId,
            String serviceClientIssuer,
            DecisionServiceClient client) {
        this.settings = settings;
        this.serviceId = serviceId;
        this.serviceClientIssuer = serviceClientIssuer;
        check(settings, authEnabled);
        if (settings.getUrl() == null) {
            this.client = null;
        } else if (client != null) {
            this.client = client;
        } else {
            this.client =
                    new DecisionServiceClient(
                            URI.create(settings.getUrl()),
                            timeout(settings),
                            Optional.ofNullable(settings.getCredentialFile())
                                    .map(Paths::get)
                                    .orElse(null));
        }
    }

    /** Startup checks (spec: refuse to start on unsafe settings) */
    private static void check(AuthzSettings settings, boolean authEnabled) {
        boolean rule =
                settings.getClaimName() != null
                        || !settings.getClaimValues().isEmpty()
                        || !settings.getClaimPrefixes().isEmpty();
        boolean decisionService =
                settings.getUrl() != null
                        || settings.getTimeout() != null
                        || !settings.getClaims().isEmpty()
                        || settings.getCredentialFile() != null;
        if ((rule || decisionService) && !authEnabled) {
            throw refuse(
                    "tesk.api.authz.required-claim and tesk.api.authz.decision-service need the"
                            + " auth profile");
        }
        if (rule) {
            if (settings.getClaimName() == null) {
                throw refuse(
                        "tesk.api.authz.required-claim.values and .prefixes need"
                                + " tesk.api.authz.required-claim.name");
            }
            if (settings.getClaimValues().isEmpty() && settings.getClaimPrefixes().isEmpty()) {
                throw refuse(
                        "tesk.api.authz.required-claim.name needs"
                                + " tesk.api.authz.required-claim.values or .prefixes");
            }
            if (settings.getClaimValues().contains("")
                    || settings.getClaimPrefixes().contains("")) {
                throw refuse(
                        "tesk.api.authz.required-claim.values and .prefixes must not contain an"
                                + " empty string");
            }
        }
        if (!decisionService) {
            return;
        }
        if (settings.getUrl() == null) {
            throw refuse("tesk.api.authz.decision-service.* needs its url");
        }
        URI url = url(settings.getUrl());
        if (settings.getIssuer() == null) {
            throw refuse(
                    "tesk.api.authz.decision-service.url needs tesk.api.authz.issuer (the issuer"
                            + " of the users' tokens, for the owner IDs)");
        }
        timeout(settings);
        if (settings.getCredentialFile() != null) {
            try {
                DecisionServiceClient.readCredential(Paths.get(settings.getCredentialFile()));
            } catch (IOException | RuntimeException e) {
                throw refuse(
                        "tesk.api.authz.decision-service.credential-file is empty or unreadable ("
                                + e.getClass().getSimpleName()
                                + ")");
            }
        }
        if ("http".equalsIgnoreCase(url.getScheme()) && !isLoopback(url.getHost())) {
            logger.warn(
                    "tesk.api.authz.decision-service.url uses plain http to a host other than"
                            + " loopback; secure the connection, e.g. with mTLS in a service mesh");
        }
    }

    private static IllegalStateException refuse(String message) {
        return new IllegalStateException(message);
    }

    /** The URL of the decision service: http(s) with a host */
    private static URI url(String value) {
        URI url;
        try {
            url = new URI(value);
        } catch (URISyntaxException e) {
            throw refuse("tesk.api.authz.decision-service.url is not a URL");
        }
        if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                || !StringUtils.hasText(url.getHost())) {
            throw refuse("tesk.api.authz.decision-service.url must be an http(s) URL with a host");
        }
        return url;
    }

    /** The timeout: seconds, more than 0 */
    private static Duration timeout(AuthzSettings settings) {
        double seconds;
        try {
            seconds =
                    settings.getTimeout() == null
                            ? AuthzSettings.DEFAULT_TIMEOUT
                            : Double.parseDouble(settings.getTimeout());
        } catch (NumberFormatException e) {
            seconds = -1;
        }
        if (!(seconds > 0) || Double.isInfinite(seconds)) {
            throw refuse("tesk.api.authz.decision-service.timeout must be a number of seconds > 0");
        }
        return Duration.ofMillis(Math.max(1, Math.round(seconds * 1000)));
    }

    /** Whether the host is a loopback address: localhost, 127.x.x.x or [::1] (nothing resolved) */
    static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || host.matches("127(\\.[0-9]{1,3}){3}")
                || "[::1]".equals(host);
    }

    /** Whether a decision service decides instead of TESK's group model (used in SpEL) */
    public boolean isDecisionService() {
        return this.client != null;
    }

    /**
     * The required claim: if set, the caller's claim must have one of the values or start with one
     * of the prefixes; else 403. Independent of the decision service and its claims.
     *
     * @throws AccessDeniedException if the rule is set and the caller doesn't pass it
     */
    public void gate(User user) {
        String name = this.settings.getClaimName();
        if (name == null) {
            return;
        }
        Object value = ClaimLookup.lookup(user.getClaims(), name).orElse(null);
        Collection<?> values =
                value instanceof String
                        ? Collections.singletonList(value)
                        : value instanceof List ? (List<?>) value : null;
        boolean admitted =
                values != null
                        && values.stream().allMatch(item -> item instanceof String)
                        && values.stream().map(String.class::cast).anyMatch(this::matches);
        if (!admitted) {
            throw new AccessDeniedException("Access is denied");
        }
    }

    private boolean matches(String value) {
        return this.settings.getClaimValues().contains(value)
                || this.settings.getClaimPrefixes().stream().anyMatch(value::startsWith);
    }

    /**
     * The issuer of the caller's token: the validated issuer of a service client's token, or the
     * configured issuer of the users' tokens; empty if not known
     */
    private Optional<String> issuer(User user) {
        return Optional.ofNullable(
                user.getIssuer() != null ? user.getIssuer() : this.settings.getIssuer());
    }

    /** The {@code sub} of the caller: of the user info or token, else the principal's ID */
    private static String sub(User user) {
        Object sub = user.getClaims().get("sub");
        return sub instanceof String ? (String) sub : user.getUsername();
    }

    /**
     * The owner ID of the caller, {@code <iss>|<sub>}, as stamped on new tasks and sent as {@code
     * subject.id}; empty if the issuer isn't known (no tesk.api.authz.issuer for users). For
     * service clients, the {@code sub} is the client ID, as in the creator label, also if their
     * token's {@code sub} differs (tesk.api.authorisation.service-clients.require-client-subject
     * false): their tasks are the client's.
     */
    public Optional<String> ownerId(User user) {
        String sub = user.isServiceClient() ? user.getUsername() : sub(user);
        return issuer(user).map(iss -> iss + OWNER_SEPARATOR + sub);
    }

    /**
     * The owner of a task created before owners were stamped, from its creator's ID: only if every
     * creator was a user of the configured issuer (no service clients of another issuer), else
     * unknown
     */
    public Optional<String> ownerOfCreator(String creatorId) {
        String issuer = this.settings.getIssuer();
        if (creatorId == null
                || issuer == null
                || (this.serviceClientIssuer != null && !this.serviceClientIssuer.equals(issuer))) {
            return Optional.empty();
        }
        return Optional.of(issuer + OWNER_SEPARATOR + creatorId);
    }

    /**
     * Checks the creation of a task: allowed by the decision service (if set) for the task set
     * {@code own}, with a projection of the task (see {@link TaskProjection})
     *
     * @throws AccessDeniedException if denied
     * @throws AuthorisationUnavailableException if the decision service failed
     */
    public void checkCreate(User user, TesTask task) {
        if (!isDecisionService()) {
            return;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task", TaskProjection.of(task));
        if (!ask(user, "CreateTask", resource("tes_task_set", "own", properties), view(null))) {
            throw new AccessDeniedException("Access is denied");
        }
    }

    /**
     * Whether the caller may get the task; asked also for missing tasks, so that missing tasks and
     * tasks of others look the same (both 404, both 503 while the decision service is down)
     *
     * @param meta owner, state and tags of the task, or null if it doesn't exist
     */
    public boolean mayRead(User user, String taskId, TaskMeta meta, TaskView view) {
        return ask(user, "GetTask", taskResource(taskId, meta), view(view));
    }

    /**
     * Checks the cancellation of a task. If cancelling is denied, or the task doesn't exist,
     * GetTask is asked as well (the same calls for missing tasks as for tasks of others): 403 if
     * the task exists and the caller may get it, else 404.
     *
     * @param meta owner, state and tags of the task, or null if it doesn't exist
     * @throws TaskNotFoundException 404
     * @throws AccessDeniedException 403
     */
    public void checkCancel(User user, String taskId, TaskMeta meta) {
        Map<String, Object> resource = taskResource(taskId, meta);
        boolean allowed = ask(user, "CancelTask", resource, view(null));
        if (meta != null && allowed) {
            return;
        }
        boolean readable = ask(user, "GetTask", resource, view(null));
        if (meta != null && readable) {
            throw new AccessDeniedException("Access is denied");
        }
        throw new TaskNotFoundException(taskId);
    }

    /**
     * Whether the caller lists all tasks (true) or only their own (false), as the decision service
     * allows the task sets {@code all} and {@code own}
     *
     * @throws AccessDeniedException if neither is allowed
     */
    public boolean listsAll(User user, TaskView view) {
        boolean all = ask(user, "ListTasks", resource("tes_task_set", "all", null), view(view));
        boolean own = ask(user, "ListTasks", resource("tes_task_set", "own", null), view(view));
        if (!all && !own) {
            throw new AccessDeniedException("Access is denied");
        }
        return all;
    }

    private static Map<String, Object> view(TaskView view) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (view != null) {
            properties.put("view", view.name());
        }
        return properties;
    }

    private static Map<String, Object> taskResource(String taskId, TaskMeta meta) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (meta != null) {
            meta.getOwner().ifPresent(owner -> properties.put("owner", owner));
            properties.put("state", meta.getState());
            properties.put("tags", meta.getTags());
        }
        return resource("tes_task", taskId, properties);
    }

    private static Map<String, Object> resource(
            String type, String id, Map<String, Object> properties) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", type);
        resource.put("id", id);
        if (properties != null) {
            resource.put("properties", properties);
        }
        return resource;
    }

    /** The AuthZEN subject: the validated identity, never the token */
    Map<String, Object> subject(User user) {
        Map<String, Object> properties = new LinkedHashMap<>();
        issuer(user).ifPresent(iss -> properties.put("iss", iss));
        properties.put("sub", sub(user));
        if (user.isServiceClient()) {
            properties.put("client_id", user.getUsername());
            Object scope = user.getClaims().get("scope");
            if (scope instanceof String) {
                properties.put("scope", Arrays.asList(((String) scope).trim().split("\\s+")));
            } else if (scope instanceof List) {
                properties.put("scope", scope);
            }
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        for (String name : this.settings.getClaims()) {
            ClaimLookup.lookup(user.getClaims(), name).ifPresent(value -> claims.put(name, value));
        }
        properties.put("claims", claims);
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("type", "user");
        subject.put("id", ownerId(user).orElseThrow(IllegalStateException::new));
        subject.put("properties", properties);
        return subject;
    }

    /** Asks the decision service once; any failure is a 503 */
    private boolean ask(
            User user, String action, Map<String, Object> resource, Map<String, Object> aprops) {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> actionObject = new LinkedHashMap<>();
        actionObject.put("name", action);
        actionObject.put("properties", aprops);
        Map<String, Object> tes = new LinkedHashMap<>();
        tes.put("profile", PROFILE);
        tes.put("service_id", this.serviceId.get());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject(user));
        body.put("action", actionObject);
        body.put("resource", resource);
        body.put("context", Collections.singletonMap("tes", tes));
        boolean decision;
        try {
            decision = this.client.evaluate(body, requestId);
        } catch (DecisionServiceClient.Failure e) {
            logger.error(
                    "Decision service failed ({}) for {} on {} {}, request {}",
                    e.getMessage(),
                    action,
                    resource.get("type"),
                    resource.get("id"),
                    requestId);
            throw new AuthorisationUnavailableException();
        }
        logger.info(
                "Decision {} for {} on {} {}, request {}",
                decision,
                action,
                resource.get("type"),
                resource.get("id"),
                requestId);
        return decision;
    }

    /** Owner, state and tags of a stored task, as sent to the decision service */
    public static final class TaskMeta {
        private final String owner;
        private final String state;
        private final Map<String, String> tags;

        /**
         * @param owner the exact owner ID, or null if not known (it is then left out)
         * @param state the task's state
         * @param tags the task's tags (null as none)
         */
        public TaskMeta(String owner, String state, Map<String, String> tags) {
            this.owner = owner;
            this.state = state;
            this.tags = tags == null ? Collections.emptyMap() : tags;
        }

        public Optional<String> getOwner() {
            return Optional.ofNullable(owner);
        }

        public String getState() {
            return state;
        }

        public Map<String, String> getTags() {
            return tags;
        }
    }
}
