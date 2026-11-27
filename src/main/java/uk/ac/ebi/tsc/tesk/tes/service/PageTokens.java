package uk.ac.ebi.tsc.tesk.tes.service;

import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageTokenException;

/**
 * Page tokens of lists, bound to the list scope they were issued under (tes-authz spec, section
 * 3.5). A Kubernetes continue token points at a key, not at a label selector: a token of a page of
 * the caller's own tasks, used for a page of all tasks, would skip other owners' tasks. So the
 * tokens of pages of all tasks carry the prefix {@value #ALL}, which no continue token has (they
 * are base64), and a token of the other scope is refused ({@code 400}). Tokens of pages of the
 * caller's own tasks are Kubernetes's continue tokens as they are, as without a decision service.
 */
final class PageTokens {

    /** The prefix of the tokens of pages of all tasks */
    static final String ALL = "all.";

    private PageTokens() {}

    /**
     * The continue token of Kubernetes for a page token
     *
     * @param pageToken the client's page token, or null (or empty) for the first page
     * @param all whether the page lists all tasks (list scope {@code all})
     * @return the continue token, or null for the first page
     * @throws InvalidPageTokenException if the token was issued under the other scope
     */
    static String continueToken(String pageToken, boolean all) {
        if (pageToken == null) {
            return null;
        }
        if (pageToken.isEmpty() && all) {
            // an empty page_token parameter: the first page, as for the caller's own tasks
            return null;
        }
        if (pageToken.startsWith(ALL) != all) {
            throw new InvalidPageTokenException();
        }
        if (!all) {
            // unchanged, as without a decision service
            return pageToken;
        }
        String token = pageToken.substring(ALL.length());
        if (token.isEmpty()) {
            throw new InvalidPageTokenException();
        }
        return token;
    }

    /**
     * The page token for a continue token of Kubernetes
     *
     * @param continueToken the continue token, or null if there is no next page
     * @param all whether the page lists all tasks (list scope {@code all})
     * @return the page token, or null if there is no next page
     */
    static String pageToken(String continueToken, boolean all) {
        if (continueToken == null || continueToken.isEmpty() || !all) {
            return continueToken;
        }
        return ALL + continueToken;
    }
}
