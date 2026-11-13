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
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * The result of validating an access token ({@link UserInfoTokenIntrospector}, {@link
 * ServiceClientTokenServices}): the {@link User} (a user or a service client), its authorities (the
 * group names) and the claims it was built from. {@link #toAuthentication} turns it into the
 * authentication of the request, whose principal is the {@link User}, as the authorisation rules
 * (e.g. of {@code TesService}) expect.
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

    /** The user or service client */
    public User getUser() {
        return this.user;
    }

    /** The claims (of the user info, or of a service client's token) */
    @Override
    public Map<String, Object> getAttributes() {
        return this.attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
    }

    /** The ID of the user (the {@code sub} of the user info) or of the service client */
    @Override
    public String getName() {
        return this.user.getUsername();
    }

    /**
     * The authentication of a request with a valid access token (an {@code
     * OpaqueTokenAuthenticationConverter}): the {@link User} as principal, with the authorities;
     * the token isn't kept in it
     */
    static Authentication toAuthentication(
            String accessToken, OAuth2AuthenticatedPrincipal principal) {
        if (!(principal instanceof AuthenticatedUser)) {
            // not expected: both introspectors return an AuthenticatedUser
            throw new InvalidBearerTokenException("Invalid access token");
        }
        AuthenticatedUser authenticated = (AuthenticatedUser) principal;
        return new UsernamePasswordAuthenticationToken(
                authenticated.getUser(), "N/A", authenticated.getAuthorities());
    }
}
