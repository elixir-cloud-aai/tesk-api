package uk.ac.ebi.tsc.tesk.config.security.token;

/**
 * Validates the access token of a request and tells who the caller is. The one seam of token
 * handling: TESK API selects the implementation by configuration ({@code
 * tesk.api.auth.token-validator}, {@link TokenValidators}), so that other kinds of validation (e.g.
 * of opaque tokens by introspection, RFC 7662) can be added later without changes to the callers.
 *
 * <p>Implementations check the token completely (issuer, signature or introspection, lifetime,
 * type, audience) and classify the caller; they never log the token.
 */
public interface TokenValidator {

    /**
     * @param token the bearer token of the request
     * @return the caller, if the token is valid
     * @throws InvalidTokenException if it isn't, with a reason that holds neither the token nor
     *     values from it that weren't verified
     */
    TokenPrincipal validate(String token);
}
