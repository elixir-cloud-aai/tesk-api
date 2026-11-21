package uk.ac.ebi.tsc.tesk.tes.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 503 - the decision service (tesk.api.authz.decision-service) failed: it timed out, couldn't be
 * reached, or didn't answer with a boolean decision. The request is denied; the message is generic,
 * the cause is logged.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class AuthorisationUnavailableException extends RuntimeException {
    private static final String MESSAGE = "Authorisation service unavailable";

    public AuthorisationUnavailableException() {
        super(MESSAGE);
    }
}
