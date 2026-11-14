package uk.ac.ebi.tsc.tesk.config.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** The principal of a validated token, and the authentication made from it */
public class AuthenticatedUserTest {

    private static final String TOKEN = "secret-token";

    private final User user = User.builder("user@example.org").name("A User").build();
    private final List<GrantedAuthority> authorities =
            AuthorityUtils.createAuthorityList("group:A", "group:B");

    private AuthenticatedUser authenticatedUser() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "user@example.org");
        return new AuthenticatedUser(user, claims, authorities);
    }

    @Test
    public void name_isTheUserId() {
        AuthenticatedUser principal = authenticatedUser();
        assertEquals("user@example.org", principal.getName());
        assertSame(user, principal.getUser());
        assertEquals("user@example.org", principal.getAttribute("sub"));
        assertEquals(authorities, principal.getAuthorities());
    }

    @Test
    public void attributesAndAuthorities_areUnmodifiable() {
        AuthenticatedUser principal = authenticatedUser();
        assertThrows(
                UnsupportedOperationException.class,
                () -> principal.getAttributes().put("sub", "other"));
        assertThrows(UnsupportedOperationException.class, () -> principal.getAuthorities().clear());
    }

    @Test
    public void withoutUser_isRefused() {
        assertThrows(
                NullPointerException.class,
                () -> new AuthenticatedUser(null, Collections.emptyMap(), Collections.emptyList()));
    }

    /** The authentication has the user as principal, the authorities, and not the token */
    @Test
    public void authentication_hasTheUser() {
        Authentication authentication =
                AuthenticatedUser.toAuthentication(TOKEN, authenticatedUser());

        assertTrue(authentication.isAuthenticated());
        assertSame(user, authentication.getPrincipal());
        assertEquals("user@example.org", authentication.getName());
        assertEquals(authorities, authentication.getAuthorities());
        assertEquals("N/A", authentication.getCredentials());
    }

    @Test
    public void authentication_ofAnotherPrincipal_isRefused() {
        OAuth2AuthenticatedPrincipal other =
                new DefaultOAuth2AuthenticatedPrincipal(
                        Collections.singletonMap("sub", "123"), Collections.emptyList());
        assertThrows(
                InvalidBearerTokenException.class,
                () -> AuthenticatedUser.toAuthentication(TOKEN, other));
    }
}
