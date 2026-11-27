package uk.ac.ebi.tsc.tesk.config.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenPrincipal;

/**
 * The result of validating an access token ({@link TokenValidatorIntrospector}): the {@link User}
 * (a user or a service client, identified by its owner ID {@code <iss>|<sub>}), its authorities
 * (none since the group model was removed, #225; kept for Spring Security's principal) and the
 * claims of the token. {@link #toAuthentication} turns it into the authentication of the request,
 * whose principal is the {@link User}, as the authorisation rules (e.g. of {@code TesService})
 * expect.
 */
public final class AuthenticatedUser implements OAuth2AuthenticatedPrincipal {

    private final User user;
    private final Map<String, Object> attributes;
    private final List<GrantedAuthority> authorities;

    AuthenticatedUser(
            User user,
            Map<String, Object> attributes,
            Collection<? extends GrantedAuthority> authorities) {
        this.user = Objects.requireNonNull(user);
        this.attributes = Collections.unmodifiableMap(attributes);
        this.authorities = Collections.unmodifiableList(new ArrayList<>(authorities));
    }

    /**
     * The caller of a valid token: its owner ID is {@code <iss>|<sub>} ({@link
     * TokenPrincipal#owner()}), its attributes are the token's claims
     */
    static AuthenticatedUser of(TokenPrincipal principal) {
        User user =
                User.builder(principal.owner())
                        .serviceClient(principal.kind() == TokenPrincipal.Kind.SERVICE_CLIENT)
                        .principal(principal)
                        .build();
        return new AuthenticatedUser(user, principal.claims(), AuthorityUtils.NO_AUTHORITIES);
    }

    /** The user or service client */
    public User getUser() {
        return this.user;
    }

    /** The claims of the token */
    @Override
    public Map<String, Object> getAttributes() {
        return this.attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
    }

    /** The owner ID of the user or service client ({@code <iss>|<sub>}) */
    @Override
    public String getName() {
        return this.user.getUsername();
    }

    /**
     * The authentication of a request with a valid access token (an {@code
     * OpaqueTokenAuthenticationConverter}): the {@link User} as principal, with the authorities
     *
     * @param accessToken the token, which that interface passes; deliberately not kept in the
     *     authentication
     * @param principal the result of the introspector
     */
    static Authentication toAuthentication(
            String accessToken, OAuth2AuthenticatedPrincipal principal) {
        if (!(principal instanceof AuthenticatedUser)) {
            // not expected: the introspector returns an AuthenticatedUser
            throw new InvalidBearerTokenException(
                    TokenValidatorIntrospector.INVALID_TOKEN_DESCRIPTION);
        }
        AuthenticatedUser authenticated = (AuthenticatedUser) principal;
        return new UsernamePasswordAuthenticationToken(
                authenticated.getUser(), "N/A", authenticated.getAuthorities());
    }
}
