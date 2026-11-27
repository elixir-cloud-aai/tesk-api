package uk.ac.ebi.tsc.tesk.config.security;

import java.util.Objects;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import uk.ac.ebi.tsc.tesk.config.security.token.InvalidTokenException;
import uk.ac.ebi.tsc.tesk.config.security.token.IssuerUnavailableException;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenValidator;

/**
 * Authenticates the bearer token of a request with the {@link TokenValidator}, for Spring
 * Security's resource server: a valid token gives an {@link AuthenticatedUser}, whose owner ID is
 * {@code <iss>|<sub>}; a token that the validator refuses gives {@code 401} with {@code
 * invalid_token}, and one that it can't check now ({@link IssuerUnavailableException}) gives {@code
 * 503} ({@link CustomAuthenticationEntryPoint}). The validator logs why (never the token); the
 * answer names no reason, so that it tells callers nothing about the checks.
 */
public final class TokenValidatorIntrospector implements OpaqueTokenIntrospector {

    /** Description of the error for all tokens that the validator refuses */
    static final String INVALID_TOKEN = "Invalid access token";

    private final TokenValidator validator;

    public TokenValidatorIntrospector(TokenValidator validator) {
        this.validator = Objects.requireNonNull(validator);
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        try {
            return AuthenticatedUser.of(this.validator.validate(token));
        } catch (InvalidTokenException e) {
            throw new BadOpaqueTokenException(INVALID_TOKEN);
        } catch (IssuerUnavailableException e) {
            throw new TokenCheckUnavailableException();
        }
    }

    /**
     * The token couldn't be checked now, e.g. as the keys of its issuer can't be fetched: {@code
     * 503}. An {@link AuthenticationException}, but not an {@code AuthenticationServiceException},
     * which Spring Security would rethrow (500) instead of passing it to the entry point.
     */
    public static final class TokenCheckUnavailableException extends AuthenticationException {

        private static final long serialVersionUID = 1L;

        /** Description of the error */
        static final String DESCRIPTION = "The access token can't be checked now; try again later";

        TokenCheckUnavailableException() {
            super(DESCRIPTION);
        }
    }
}
