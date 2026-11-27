package uk.ac.ebi.tsc.tesk.tes.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;

/**
 * Who may get, list and cancel which tasks. By default TESK API decides itself: only a task's
 * owner, the caller who created it ({@link #OWNER_ONLY}). With authentication, an operator can
 * switch any of these operations on ({@code tesk.api.authz.operations}): the decision service then
 * decides it ({@code uk.ac.ebi.tsc.tesk.authz.OperationAuthorisation}), and may widen it beyond the
 * owner, or narrow it. Create is decided by the decision service always ({@code
 * CreateAuthorisation}).
 */
public interface TaskAccess {

    /** The owner rule, which decides no operation: the default, and the fallback of {@code open} */
    TaskAccess OWNER_ONLY = new OwnerOnly();

    /** The operations of TES that a decision service can decide */
    enum Operation {
        CREATE,
        GET,
        LIST,
        CANCEL;

        /** The name in the settings ({@code tesk.api.authz.operations}), e.g. {@code get} */
        public String setting() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The operation of a name in the settings, if there is one */
        public static Optional<Operation> ofSetting(String name) {
            for (Operation operation : values()) {
                if (operation.setting().equals(name)) {
                    return Optional.of(operation);
                }
            }
            return Optional.empty();
        }
    }

    /** Which tasks a list holds (the list scope of tes-authz, spec section 3.5) */
    enum ListScope {
        /** the caller's own tasks */
        OWN,
        /** every task with an exact owner */
        ALL,
        /** none: an empty list, without reading any task */
        NONE
    }

    /** What a cancel request gets */
    enum CancelDecision {
        /** the task is cancelled (if it exists; else 404) */
        ALLOWED,
        /** 403: the caller may get the task, but not cancel it */
        FORBIDDEN,
        /** 404: as for a missing task */
        NOT_FOUND
    }

    /**
     * The facts of a stored task with an exact owner that a decision is based on
     *
     * @param owner the owner's ID ({@code <iss>|<sub>})
     * @param state the task's state
     * @param tags the task's tags (empty if it has none)
     */
    record TaskFacts(String owner, TesState state, Map<String, String> tags) {

        /** Copies the tags, which may have null values */
        public TaskFacts {
            tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
        }

        /** Whether the user owns the task, by the same comparison as {@link User#isCreatorOf} */
        public boolean isOwnedBy(User user) {
            return user.isOwner(this.owner);
        }
    }

    /**
     * Whether the decision service decides this operation; if not, TESK API decides it (owner
     * only), and the methods below aren't called for it
     */
    boolean decides(Operation operation);

    /**
     * Whether the user may get the task
     *
     * @param task the stored task, or null for a missing task or one without an exact owner
     */
    boolean mayGet(User user, String taskId, TaskFacts task, TaskView view);

    /**
     * Whether the user may cancel the task
     *
     * @param task the stored task, or null for a missing task or one without an exact owner
     */
    CancelDecision mayCancel(User user, String taskId, TaskFacts task);

    /** Which tasks the user's list holds */
    ListScope listScope(User user, TaskView view);

    /** The owner rule, as a decision of each operation */
    final class OwnerOnly implements TaskAccess {

        private OwnerOnly() {}

        /** Decides nothing: TESK API's own owner rule decides */
        @Override
        public boolean decides(Operation operation) {
            return false;
        }

        @Override
        public boolean mayGet(User user, String taskId, TaskFacts task, TaskView view) {
            return isOwner(user, task);
        }

        @Override
        public CancelDecision mayCancel(User user, String taskId, TaskFacts task) {
            return isOwner(user, task) ? CancelDecision.ALLOWED : CancelDecision.NOT_FOUND;
        }

        @Override
        public ListScope listScope(User user, TaskView view) {
            return ListScope.OWN;
        }

        /** Whether the task exists and the user owns it */
        public static boolean isOwner(User user, TaskFacts task) {
            return task != null && task.isOwnedBy(user);
        }
    }
}
