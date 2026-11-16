package uk.ac.ebi.tsc.tesk.tes.service;

import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageTokenException;

/**
 * The tasks a caller lists, as the decision service decided (tesk.api.authz): all tasks, or only
 * the caller's own. Kubernetes' continue tokens belong to the label selector they were issued for,
 * which differs between the two, so page tokens carry the scope as a prefix ({@code all:} or {@code
 * own:}); a token of another scope (e.g. after the decision changed between pages), or without a
 * prefix, is refused with 400.
 */
enum ListScope {
    ALL("all:"),
    OWN("own:");

    private final String prefix;

    ListScope(String prefix) {
        this.prefix = prefix;
    }

    /** The scope of a decision: all tasks, or the own tasks */
    static ListScope of(boolean all) {
        return all ? ALL : OWN;
    }

    /**
     * The continue token of Kubernetes in a page token of this scope
     *
     * @param pageToken the page token, or null (or empty) for the first page
     * @throws InvalidPageTokenException if the token isn't one of this scope
     */
    String continueToken(String pageToken) {
        if (pageToken == null || pageToken.isEmpty()) {
            return null;
        }
        if (!pageToken.startsWith(this.prefix) || pageToken.length() == this.prefix.length()) {
            throw new InvalidPageTokenException();
        }
        return pageToken.substring(this.prefix.length());
    }

    /** The page token of a continue token of Kubernetes (null and empty: no next page) */
    String pageToken(String continueToken) {
        return continueToken == null || continueToken.isEmpty()
                ? null
                : this.prefix + continueToken;
    }
}
