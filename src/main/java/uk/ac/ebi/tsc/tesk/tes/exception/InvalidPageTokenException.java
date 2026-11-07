package uk.ac.ebi.tsc.tesk.tes.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 400 - for listTasks: Kubernetes refused the page token as expired (410 Gone) or malformed (400
 * Bad Request). The message names the parameter, never its value.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidPageTokenException extends RuntimeException {
    private static final String MESSAGE =
            "Invalid or expired page_token; list the tasks again without page_token.";

    public InvalidPageTokenException() {
        super(MESSAGE);
    }
}
