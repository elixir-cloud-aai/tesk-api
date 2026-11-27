package uk.ac.ebi.tsc.tesk.tes.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 400 - with authentication, creating a task whose input or output URL can't be passed on to the
 * decision service: its stripping (without user info, query and fragment) leaves it out, e.g. for
 * an ambiguous authority or control characters, so that the decision service would see the file as
 * inline content. The message names the field, never the URL.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UncheckableUrlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * @param field the field, e.g. {@code inputs[0].url}
     */
    public UncheckableUrlException(String field) {
        super(field + " can't be checked: not a URL that can be passed on without credentials");
    }
}
