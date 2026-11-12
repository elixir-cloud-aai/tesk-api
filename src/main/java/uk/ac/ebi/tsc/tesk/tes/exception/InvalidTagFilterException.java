package uk.ac.ebi.tsc.tesk.tes.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 400 - for listTasks: more {@code tag_value}s than {@code tag_key}s. The message names both
 * parameters, never their values.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidTagFilterException extends RuntimeException {
    private static final String MESSAGE = "tag_value has more values than tag_key.";

    public InvalidTagFilterException() {
        super(MESSAGE);
    }
}
