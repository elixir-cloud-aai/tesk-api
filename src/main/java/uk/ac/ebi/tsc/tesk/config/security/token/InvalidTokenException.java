package uk.ac.ebi.tsc.tesk.config.security.token;

/**
 * A token that a {@link TokenValidator} refused (the request then gets {@code 401}). The message is
 * the reason, for the log; it holds neither the token nor unverified values from it.
 */
public class InvalidTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param reason why the token was refused
     */
    public InvalidTokenException(String reason) {
        super(reason);
    }
}
