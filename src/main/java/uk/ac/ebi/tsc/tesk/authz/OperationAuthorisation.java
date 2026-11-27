package uk.ac.ebi.tsc.tesk.authz;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenPrincipal;
import uk.ac.ebi.tsc.tesk.tes.exception.AuthorisationUnavailableException;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess;

/**
 * Decides get, list and cancel by asking the decision service, for the operations that the operator
 * switched on ({@code tesk.api.authz.operations}; profile {@code tes-authzen/3} of tes-authz,
 * sections 3, 3.5, 4.1 and 6). The decision service then decides alone: it may allow another
 * owner's task (widen) or deny the owner (narrow). Operations that aren't switched on are TESK
 * API's own decisions (owner only), without a request.
 *
 * <ul>
 *   <li>get: one {@code GetTask} request per task, also for a missing task ({@code properties:
 *       {}}), so that the answer doesn't tell whether a task exists; a deny, or an allow for a
 *       missing task, is {@code 404};
 *   <li>cancel: one {@code CancelTask} request; after a deny, get is decided once more (asking
 *       {@code GetTask} if get is switched on, else by the owner rule): {@code 403} if the caller
 *       may get the task and it exists, else {@code 404};
 *   <li>list: one {@code ListTasks} request per page, whose answer gives the list scope ({@link
 *       #listScope(JsonNode)}).
 * </ul>
 *
 * <p>A failed decision (no answer in time, a connection error, a status other than 200, no JSON, no
 * boolean {@code decision}, a malformed list scope) is {@code 503} if the operation fails {@code
 * closed} (the default), or the owner rule if it fails {@code open} ({@code
 * tesk.api.authz.failure.<operation>}): never allow-all. Claims come from the access token only:
 * the user info endpoint is asked on create only.
 */
public class OperationAuthorisation implements TaskAccess {

    private static final Logger logger = LoggerFactory.getLogger(OperationAuthorisation.class);

    /** A deny, for callers without a validated token */
    private static final JsonNode DENY =
            JsonNodeFactory.instance.objectNode().put("decision", false);

    private final DecisionServiceClient decisionService;
    private final EvaluationRequests requests;
    private final Set<Operation> operations;
    private final Set<Operation> failOpen;
    private final Function<Map<String, Object>, String> requestIds;

    /**
     * @param decisionService the client of the decision service
     * @param requests the builder of the requests
     * @param operations the operations that the decision service decides
     * @param failOpen the operations that fall back to the owner rule if a decision fails
     */
    OperationAuthorisation(
            DecisionServiceClient decisionService,
            EvaluationRequests requests,
            Set<Operation> operations,
            Set<Operation> failOpen) {
        this(decisionService, requests, operations, failOpen, body -> UUID.randomUUID().toString());
    }

    /** As above, with the request IDs from a function of the request (for tests) */
    OperationAuthorisation(
            DecisionServiceClient decisionService,
            EvaluationRequests requests,
            Set<Operation> operations,
            Set<Operation> failOpen,
            Function<Map<String, Object>, String> requestIds) {
        this.decisionService = Objects.requireNonNull(decisionService);
        this.requests = Objects.requireNonNull(requests);
        this.operations = copy(operations);
        this.failOpen = copy(failOpen);
        this.requestIds = Objects.requireNonNull(requestIds);
    }

    private static Set<Operation> copy(Set<Operation> operations) {
        return operations.isEmpty() ? EnumSet.noneOf(Operation.class) : EnumSet.copyOf(operations);
    }

    @Override
    public boolean decides(Operation operation) {
        return this.operations.contains(operation);
    }

    @Override
    public boolean mayGet(User user, String taskId, TaskFacts task, TaskView view) {
        return get(user, taskId, task, Map.of("view", view.name()));
    }

    /** The decision of get, with these action properties; the owner rule if it fails open */
    private boolean get(
            User user, String taskId, TaskFacts task, Map<String, Object> actionProperties) {
        return ask(Operation.GET, user, "GetTask", actionProperties, task(taskId, task))
                .map(DecisionServiceClient::isAllowed)
                .orElseGet(() -> OwnerOnly.isOwner(user, task));
    }

    @Override
    public CancelDecision mayCancel(User user, String taskId, TaskFacts task) {
        Optional<Boolean> cancel =
                ask(Operation.CANCEL, user, "CancelTask", Map.of(), task(taskId, task))
                        .map(DecisionServiceClient::isAllowed);
        if (cancel.isEmpty()) {
            // fell back to the owner rule, which decides the 404 too, without a request
            return OWNER_ONLY.mayCancel(user, taskId, task);
        }
        if (cancel.get()) {
            return CancelDecision.ALLOWED;
        }
        // denied: 403 only if the caller may get the task (no view is requested here)
        boolean readable =
                decides(Operation.GET)
                        ? get(user, taskId, task, Map.of())
                        : OwnerOnly.isOwner(user, task);
        return readable && task != null ? CancelDecision.FORBIDDEN : CancelDecision.NOT_FOUND;
    }

    @Override
    public ListScope listScope(User user, TaskView view) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", "tes_task_set");
        resource.put("id", "own");
        Optional<JsonNode> answer =
                ask(Operation.LIST, user, "ListTasks", Map.of("view", view.name()), resource);
        if (answer.isEmpty()) {
            return OWNER_ONLY.listScope(user, view);
        }
        Optional<ListScope> scope = listScope(answer.get());
        if (scope.isPresent()) {
            return scope.get();
        }
        failed(Operation.LIST, user, "malformed list scope");
        return OWNER_ONLY.listScope(user, view);
    }

    /**
     * The list scope of an answer to {@code ListTasks} (spec section 3.5): {@code none} for a deny,
     * whatever its context; for an allow, {@code context.tes.list_scope}, {@code own} if there is
     * none; empty if the context isn't an object, or the scope has another value or type (a failed
     * decision)
     */
    static Optional<ListScope> listScope(JsonNode answer) {
        if (!DecisionServiceClient.isAllowed(answer)) {
            return Optional.of(ListScope.NONE);
        }
        JsonNode context = answer.get("context");
        if (context == null) {
            return Optional.of(ListScope.OWN);
        }
        if (!context.isObject()) {
            return Optional.empty();
        }
        JsonNode tes = context.get("tes");
        if (tes == null) {
            return Optional.of(ListScope.OWN);
        }
        if (!tes.isObject()) {
            return Optional.empty();
        }
        JsonNode scope = tes.get("list_scope");
        if (scope == null) {
            return Optional.of(ListScope.OWN);
        }
        if (!scope.isString()) {
            return Optional.empty();
        }
        return switch (scope.stringValue()) {
            case "own" -> Optional.of(ListScope.OWN);
            case "all" -> Optional.of(ListScope.ALL);
            case "none" -> Optional.of(ListScope.NONE);
            default -> Optional.empty();
        };
    }

    /**
     * The resource of a task asked about (spec section 3.7): its owner, state and tags if it is
     * stored with an exact owner, else no properties
     */
    static Map<String, Object> task(String taskId, TaskFacts task) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (task != null) {
            properties.put("owner", task.owner());
            properties.put("state", task.state() == null ? null : task.state().getValue());
            properties.put("tags", task.tags());
        }
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", "tes_task");
        resource.put("id", taskId);
        resource.put("properties", properties);
        return resource;
    }

    /**
     * Asks the decision service
     *
     * @return the answer, or empty if the decision failed and the operation fails open
     * @throws AuthorisationUnavailableException if the decision failed and the operation fails
     *     closed
     */
    private Optional<JsonNode> ask(
            Operation operation,
            User user,
            String action,
            Map<String, Object> actionProperties,
            Map<String, Object> resource) {
        Optional<TokenPrincipal> principal = user.getPrincipal();
        if (principal.isEmpty()) {
            // not expected: with authentication, every caller has a validated token
            logger.warn("{} denied: the caller has no validated token", action);
            return Optional.of(DENY);
        }
        Map<String, Object> body =
                this.requests.request(
                        this.requests.subject(principal.get(), Map.of()),
                        action,
                        actionProperties,
                        resource);
        String requestId = this.requestIds.apply(body);
        JsonNode answer;
        try {
            answer = this.decisionService.answer(body, requestId);
        } catch (DecisionServiceClient.Failure e) {
            return failed(operation, user, e.getMessage() + ", request " + requestId);
        }
        JsonNode decision = answer.get("decision");
        if (decision == null || !decision.isBoolean()) {
            // a failed decision (spec section 4.1), so that the operation's failure mode applies
            return failed(
                    operation, user, "no boolean decision in the answer, request " + requestId);
        }
        logger.debug(
                "Decision service answered {} on {} {} (request {})",
                DecisionServiceClient.isAllowed(answer) ? "allow" : "deny",
                action,
                resource.get("id"),
                requestId);
        return Optional.of(answer);
    }

    /**
     * A failed decision: the owner rule (empty) if the operation fails open, else {@code 503}
     *
     * @throws AuthorisationUnavailableException if the operation fails closed
     */
    private Optional<JsonNode> failed(Operation operation, User user, String cause) {
        if (this.failOpen.contains(operation)) {
            logger.warn(
                    "Decision service failed on {} of {} ({}): falling back to the owner rule",
                    operation.setting(),
                    user.getName(),
                    cause);
            return Optional.empty();
        }
        logger.warn(
                "Decision service failed on {} of {}: {}",
                operation.setting(),
                user.getName(),
                cause);
        throw new AuthorisationUnavailableException();
    }
}
