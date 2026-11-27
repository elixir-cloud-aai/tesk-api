package uk.ac.ebi.tsc.tesk.config.security;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.io.Serializable;
import java.security.Principal;
import java.util.Collection;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import uk.ac.ebi.tsc.tesk.k8s.constant.Constants;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * The caller of a request: a user or a service client, identified by its owner ID: {@code
 * <iss>|<sub>} of its token with authentication, Spring Security's anonymous user without (noauth).
 * There are no groups: the caller gets, lists and cancels only the tasks it created ({@link
 * #isCreatorOf}), with the same owner ID.
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
public class User implements Serializable, UserDetails, Principal {

    private static final long serialVersionUID = 2L;

    /**
     * The owner ID: {@code <iss>|<sub>} of the token (for a service client, its {@code sub} is its
     * client ID), or {@code anonymousUser} without authentication
     */
    private final String userId;

    /**
     * Is this principal a service client, authenticated by its own access token as a listed client
     * of its issuer (tesk.api.auth.issuers[i].service-clients)
     */
    private final boolean serviceClient;

    User(String userId, boolean serviceClient) {
        this.userId = Objects.requireNonNull(userId);
        this.serviceClient = serviceClient;
    }

    public static User.UserBuilder builder(String userId) {
        return new User.UserBuilder(userId);
    }

    /** The ID, as {@link #getUsername()} */
    @Override
    public String getName() {
        return userId;
    }

    public boolean isServiceClient() {
        return serviceClient;
    }

    /**
     * Whether the user created the Kubernetes object: the hash label and the annotation give the
     * user's ID ({@link CreatorLabels#creatorId}); objects without them have no creator
     */
    public boolean isCreatorOf(V1ObjectMeta metadata) {
        return metadata != null && userId.equals(CreatorLabels.creatorId(metadata));
    }

    /**
     * Label selector of the user's taskmaster jobs, in addition to the job type: the hash label of
     * the user's ID. It only narrows the list down, as different IDs can have the same hash: the
     * caller keeps only the jobs the user created ({@link #isCreatorOf}).
     */
    public String getLabelSelector() {
        return Constants.LABEL_USERHASH_KEY + "=" + CreatorLabels.hash(userId);
    }

    /** No authorities: what a caller may do depends only on whether it created the task */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AuthorityUtils.NO_AUTHORITIES;
    }

    @Override
    public String getPassword() {
        return null;
    }

    /** The owner ID, unchanged */
    @Override
    public String getUsername() {
        return userId;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String toString() {
        return "User{userId='" + userId + "', serviceClient=" + serviceClient + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        User user = (User) o;
        return serviceClient == user.serviceClient && userId.equals(user.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, serviceClient);
    }

    public static class UserBuilder {
        private final String userId;
        private boolean serviceClient = false;

        UserBuilder(String userId) {
            this.userId = userId;
        }

        public UserBuilder serviceClient(boolean serviceClient) {
            this.serviceClient = serviceClient;
            return this;
        }

        public User build() {
            return new User(userId, serviceClient);
        }
    }
}
