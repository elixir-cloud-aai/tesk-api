package uk.ac.ebi.tsc.tesk.config.security;

/**
 * Which tasks a user may get, list and cancel (tesk.api.authorisation.task-visibility, issue #218).
 * Groups decide who may create tasks either way.
 */
public enum TaskVisibility {
    /**
     * Only the own tasks, whatever the user's groups: super admins and team admins see no tasks of
     * other users (the default)
     */
    OWNER,

    /**
     * As the groups decide: team admins see all tasks of their teams, super admins all tasks of the
     * installation (the behaviour before issue #218)
     */
    GROUP
}
