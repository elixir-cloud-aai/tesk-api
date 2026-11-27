package uk.ac.ebi.tsc.tesk.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import uk.ac.ebi.tsc.tesk.config.security.token.InvalidTokenException;
import uk.ac.ebi.tsc.tesk.config.security.token.IssuerUnavailableException;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenPrincipal;

/** The validator's answers as Spring Security's resource server takes them */
public class TokenValidatorIntrospectorTest {

    private static final String ISSUER = "https://issuer.example/";

    @Test
    public void user_isOwnedByIssuerAndSubject() {
        TokenPrincipal principal =
                new TokenPrincipal(
                        ISSUER,
                        "user-1",
                        TokenPrincipal.Kind.USER,
                        "portal",
                        Map.of("sub", "user-1", "iss", ISSUER));
        AuthenticatedUser user =
                (AuthenticatedUser)
                        new TokenValidatorIntrospector(token -> principal).introspect("t");
        assertEquals(ISSUER + "|user-1", user.getName());
        assertEquals(ISSUER + "|user-1", user.getUser().getUsername());
        assertFalse(user.getUser().isServiceClient());
        assertEquals("user-1", user.getAttribute("sub"));
        assertTrue(user.getAuthorities().isEmpty());
    }

    @Test
    public void serviceClient_isOwnedByIssuerAndClientId() {
        TokenPrincipal principal =
                new TokenPrincipal(
                        ISSUER, "gateway", TokenPrincipal.Kind.SERVICE_CLIENT, "gateway", Map.of());
        AuthenticatedUser user =
                (AuthenticatedUser)
                        new TokenValidatorIntrospector(token -> principal).introspect("t");
        assertEquals(ISSUER + "|gateway", user.getName());
        assertTrue(user.getUser().isServiceClient());
    }

    /** The answer names no reason: the validator logs it */
    @Test
    public void refusedToken_isInvalid() {
        TokenValidatorIntrospector introspector =
                new TokenValidatorIntrospector(token -> {
                    throw new InvalidTokenException("wrong audience");
                });
        BadOpaqueTokenException e =
                assertThrows(BadOpaqueTokenException.class, () -> introspector.introspect("t"));
        assertEquals(TokenValidatorIntrospector.INVALID_TOKEN, e.getMessage());
    }

    /**
     * Unavailable keys are no authentication service exception (checked by the compiler), which
     * Spring Security would rethrow (500) instead of answering with the entry point (503)
     */
    @Test
    public void unavailableIssuer_isTemporarilyUnavailable() {
        TokenValidatorIntrospector introspector =
                new TokenValidatorIntrospector(token -> {
                    throw new IssuerUnavailableException("keys of the issuer unavailable");
                });
        TokenValidatorIntrospector.TokenCheckUnavailableException e =
                assertThrows(
                        TokenValidatorIntrospector.TokenCheckUnavailableException.class,
                        () -> introspector.introspect("t"));
        assertEquals(
                TokenValidatorIntrospector.TokenCheckUnavailableException.DESCRIPTION,
                e.getMessage());
    }

    @Test
    public void withoutValidator_isRefused() {
        assertThrows(NullPointerException.class, () -> new TokenValidatorIntrospector(null));
    }
}
