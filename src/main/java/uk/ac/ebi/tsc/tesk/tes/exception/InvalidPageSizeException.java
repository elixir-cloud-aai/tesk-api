package uk.ac.ebi.tsc.tesk.tes.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 400 - for listTasks: page_size out of range. The message names the allowed range. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidPageSizeException extends RuntimeException {
    private static final String MESSAGE = "page_size must be between 1 and %d.";

    public InvalidPageSizeException(long maxPageSize) {
        super(String.format(MESSAGE, maxPageSize));
    }
}
