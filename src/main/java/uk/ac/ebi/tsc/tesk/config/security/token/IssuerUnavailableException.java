package uk.ac.ebi.tsc.tesk.config.security.token;

/**
 * The token couldn't be checked because the keys of its issuer are unavailable (e.g. the issuer is
 * down): not the caller's fault, so the request should get {@code 503}, not {@code 401}, and the
 * client may retry. The message holds no token.
 */
public class IssuerUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param reason why the token couldn't be checked
     */
    public IssuerUnavailableException(String reason) {
        super(reason);
    }
}
