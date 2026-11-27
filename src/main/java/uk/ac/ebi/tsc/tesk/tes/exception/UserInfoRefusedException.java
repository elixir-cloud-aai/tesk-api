package uk.ac.ebi.tsc.tesk.tes.exception;

import uk.ac.ebi.tsc.tesk.config.security.TokenValidatorIntrospector;

/**
 * 401 - the user info endpoint of the token's issuer refused the token (401 or 403), or answered
 * for another subject, when TESK API looked up the caller's claims to create a task. Answered like
 * other invalid tokens, with {@code WWW-Authenticate} ({@code CustomErrorHandler}).
 */
public class UserInfoRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The description of the error, as for other invalid tokens */
    public static final String MESSAGE = TokenValidatorIntrospector.INVALID_TOKEN_DESCRIPTION;

    public UserInfoRefusedException() {
        super(MESSAGE);
    }
}
