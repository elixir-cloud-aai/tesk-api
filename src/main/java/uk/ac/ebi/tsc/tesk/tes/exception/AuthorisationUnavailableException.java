package uk.ac.ebi.tsc.tesk.tes.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 503 - the right to create a task, or to get, list or cancel tasks where the decision service
 * decides that and fails closed (tesk.api.authz.operations, tesk.api.authz.failure.*), can't be
 * decided now: the decision service (tesk.api.authz.decision-service) timed out, couldn't be
 * reached, or didn't answer with JSON (or, for a list, with a valid list scope), or the caller's
 * claims couldn't be looked up at the user info endpoint of its issuer. The request is denied; the
 * message is generic, the cause is logged. Answered like tokens that can't be checked now, with the
 * OAuth 2.0 error {@code temporarily_unavailable} ({@code CustomErrorHandler}).
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class AuthorisationUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String MESSAGE = "Authorisation unavailable; try again later";

    public AuthorisationUnavailableException() {
        super(MESSAGE);
    }
}
