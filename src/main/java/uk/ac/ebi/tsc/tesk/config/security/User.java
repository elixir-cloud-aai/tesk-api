package uk.ac.ebi.tsc.tesk.config.security;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.io.Serializable;
import java.security.Principal;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import uk.ac.ebi.tsc.tesk.k8s.constant.Constants;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;
import uk.ac.ebi.tsc.tesk.tes.model.TesTaskLog;

/**
 * POJO for auhenticated user's data
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
public class User implements Serializable, UserDetails, Principal {

    private static final long serialVersionUID = 1L;

    private String userId;
    private String preferredUsername;
    private String name;
    private String givenName;
    private String familyName;
    private String email;

    /** Full list of elixir group full names, to which the user belongs */
    private Set<String> allGroups;

    /** Is the user a TESK installation admin (belongs to a group with that meaning) */
    private boolean teskAdmin = false;

    /**
     * Is the user authorised without groups, by a value of the claim configured in
     * tesk.api.authorisation.claim (always false if that setting is off)
     */
    private boolean claimAuthorised = false;

    /**
     * Is this principal a service client, authenticated by an access token of an allow-listed
     * client (tesk.api.authorisation.service-clients) instead of the user info; its ID is the
     * client's ID, and it has no groups
     */
    private boolean serviceClient = false;

    /**
     * Whether tasks created before the hash label, with only the old label {@code creator-user-id},
     * are the user's if their label has the user's ID with {@code @} replaced by {@code _}
     * (tesk.api.authorisation.legacy-user-labels; see {@link CreatorLabels})
     */
    private boolean legacyUserLabels = false;

    /**
     * List of 'organisational' groups, to which the user belongs (last part of Elixir group names
     * only (see {@link ElixirPrincipalExtractor} to see, how that information is retrieved from
     * allGroups) TES task will be 'owned' by one of the groups, to which the creating user belongs
     * (or by no group, if the user does not belong to any)
     */
    private Set<String> teskMemberedGroups;

    /**
     * List of 'organisational' groups, where the user is an admin (see {@link
     * ElixirPrincipalExtractor} to see, how that information is retrieved from allGroups) TES task
     * will be 'owned' by one of the groups, to which the creating user belongs (or by no group, if
     * the user does not belong to any)
     */
    private Set<String> teskManagedGroups;

    public String getName() {
        return name;
    }

    public boolean isTeskAdmin() {
        return teskAdmin;
    }

    public boolean isClaimAuthorised() {
        return claimAuthorised;
    }

    public boolean isServiceClient() {
        return serviceClient;
    }

    /**
     * Whether the principal may create tasks without a group, and list, get and cancel its own
     * tasks without a group: users authorised by the claim (tesk.api.authorisation.claim), and
     * service clients (tesk.api.authorisation.service-clients)
     */
    public boolean isAuthorisedWithoutGroup() {
        return claimAuthorised || serviceClient;
    }

    public boolean isLegacyUserLabels() {
        return legacyUserLabels;
    }

    /**
     * Whether the user created the Kubernetes object: the hash label and the annotation give the
     * user's ID, or, for objects with only the old label and only if {@link #legacyUserLabels} is
     * on, the old label has the user's ID in the old encoding
     */
    public boolean isCreatorOf(V1ObjectMeta metadata) {
        if (CreatorLabels.isLegacy(metadata)) {
            return isLegacyCreator(CreatorLabels.legacyCreator(metadata));
        }
        return userId.equals(CreatorLabels.creatorId(metadata));
    }

    /**
     * Whether the user created the task of the log, as {@link #isCreatorOf}: its metadata {@code
     * USER_ID} holds the ID, or the value of the old label if {@link
     * TesTaskLog#isUserIdFromLegacyLabel()}
     */
    public boolean isCreator(TesTaskLog log) {
        if (log == null) {
            return false;
        }
        Map<String, String> metadata = log.getMetadata();
        String creator = metadata == null ? null : metadata.get("USER_ID");
        if (log.isUserIdFromLegacyLabel()) {
            return isLegacyCreator(creator);
        }
        return userId.equals(creator);
    }

    private boolean isLegacyCreator(String legacyLabel) {
        return legacyUserLabels && CreatorLabels.legacyValue(userId).equals(legacyLabel);
    }

    public boolean isGroupMember(String groupName) {
        return teskMemberedGroups.contains(groupName);
    }

    public boolean isMember() {
        return !teskMemberedGroups.isEmpty();
    }

    public String getAnyGroup() {
        if (!isMember()) {
            return null;
        }
        return teskMemberedGroups.iterator().next();
    }

    public boolean isGroupManager(String groupName) {
        return teskManagedGroups.contains(groupName);
    }

    public boolean isManager() {
        return !teskManagedGroups.isEmpty();
    }

    /**
     * Label selector of the taskmaster jobs that the user may list, in addition to the job type:
     * none for admins (all tasks); the groups (and, unless a manager of one, the own tasks in them)
     * for group members and managers; the own tasks without a group for users authorised by the
     * claim only (tesk.api.authorisation.claim) and for service clients. Null also for users
     * without any of these rights, who may not list tasks at all (see {@code
     * TesService#listTasks}). The own tasks are selected by the hash label, which only narrows the
     * list down: the caller checks the creator of each job ({@link #isCreatorOf}). With {@link
     * #legacyUserLabels}, the selector can't name the creator, as tasks may have either label, and
     * only selects by group.
     */
    public String getLabelSelector() {
        Set<String> allTeskGroups = new LinkedHashSet<>(this.teskMemberedGroups);
        allTeskGroups.addAll(this.teskManagedGroups);
        if (isTeskAdmin()) {
            return null;
        }
        if (isMember() || isManager()) {
            StringBuilder sb = new StringBuilder();
            sb.append(Constants.LABEL_GROUPNAME_KEY)
                    .append(" in (")
                    .append(StringUtils.collectionToCommaDelimitedString(allTeskGroups))
                    .append(")");
            if (!isManager() && !legacyUserLabels) {
                sb.append(",").append(creatorSelector());
            }
            return sb.toString();
        }
        if (isAuthorisedWithoutGroup()) {
            // authorised without groups: only the own tasks without a group (admins returned
            // above, so that they keep seeing all tasks)
            String noGroup = "!" + Constants.LABEL_GROUPNAME_KEY;
            return legacyUserLabels ? noGroup : creatorSelector() + "," + noGroup;
        }
        return null;
    }

    private String creatorSelector() {
        return Constants.LABEL_USERHASH_KEY + "=" + CreatorLabels.hash(userId);
    }

    User(
            String userId,
            String preferredUsername,
            String name,
            String givenName,
            String familyName,
            String email,
            Set<String> allGroups,
            Set<String> teskMemberedGroups,
            Set<String> teskManagedGroups,
            boolean teskAdmin,
            boolean claimAuthorised,
            boolean serviceClient,
            boolean legacyUserLabels) {
        this.userId = userId;
        this.preferredUsername = preferredUsername;
        this.name = name;
        this.givenName = givenName;
        this.familyName = familyName;
        this.email = email;
        this.allGroups = allGroups;
        this.teskMemberedGroups = teskMemberedGroups;
        this.teskManagedGroups = teskManagedGroups;
        this.teskAdmin = teskAdmin;
        this.claimAuthorised = claimAuthorised;
        this.serviceClient = serviceClient;
        this.legacyUserLabels = legacyUserLabels;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (this.allGroups.isEmpty()) {
            // no groups (e.g. service clients): no authorities, rather than an empty one
            return AuthorityUtils.NO_AUTHORITIES;
        }
        return AuthorityUtils.createAuthorityList(
                StringUtils.collectionToCommaDelimitedString(this.allGroups));
    }

    @Override
    public String getPassword() {
        return null;
    }

    /** The user's ID (the OIDC {@code sub}), unchanged */
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

    public static User.UserBuilder builder(String userId) {
        return new User.UserBuilder(userId);
    }

    @Override
    public String toString() {
        return "User{"
                + "userId='"
                + userId
                + '\''
                + ", preferredUsername='"
                + preferredUsername
                + '\''
                + ", name='"
                + name
                + '\''
                + ", givenName='"
                + givenName
                + '\''
                + ", familyName='"
                + familyName
                + '\''
                + ", email='"
                + email
                + '\''
                + ", allGroups=("
                + StringUtils.collectionToCommaDelimitedString(allGroups)
                + ")"
                + ", memberedGroups=("
                + StringUtils.collectionToCommaDelimitedString(teskMemberedGroups)
                + ")"
                + ", managedGroups=("
                + StringUtils.collectionToCommaDelimitedString(teskManagedGroups)
                + ")"
                + ", iasAdmin="
                + isTeskAdmin()
                + ", claimAuthorised="
                + isClaimAuthorised()
                + ", serviceClient="
                + isServiceClient()
                + ", legacyUserLabels="
                + isLegacyUserLabels()
                + "}";
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
        return teskAdmin == user.teskAdmin
                && claimAuthorised == user.claimAuthorised
                && serviceClient == user.serviceClient
                && legacyUserLabels == user.legacyUserLabels
                && Objects.equals(userId, user.userId)
                && Objects.equals(preferredUsername, user.preferredUsername)
                && Objects.equals(name, user.name)
                && Objects.equals(givenName, user.givenName)
                && Objects.equals(familyName, user.familyName)
                && Objects.equals(email, user.email)
                && allGroups.equals(user.allGroups)
                && teskMemberedGroups.equals(user.teskMemberedGroups)
                && teskManagedGroups.equals(user.teskManagedGroups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                userId,
                preferredUsername,
                name,
                givenName,
                familyName,
                email,
                allGroups,
                teskAdmin,
                claimAuthorised,
                serviceClient,
                legacyUserLabels,
                teskMemberedGroups,
                teskManagedGroups);
    }

    public static class UserBuilder {
        private String userId;
        private String preferredUsername;
        private String name;
        private String givenName;
        private String familyName;
        private String email;
        private Set<String> allGroups = new HashSet<>();
        private Set<String> teskMemberedGroups = new HashSet<>();
        private Set<String> teskManagedGroups = new HashSet<>();
        private boolean teskAdmin = false;
        private boolean claimAuthorised = false;
        private boolean serviceClient = false;
        private boolean legacyUserLabels = false;

        UserBuilder(String userId) {
            this.userId = userId;
        }

        public UserBuilder preferredUsername(String preferredUsername) {
            this.preferredUsername = preferredUsername;
            return this;
        }

        public UserBuilder name(String name) {
            this.name = name;
            return this;
        }

        public UserBuilder givenName(String givenName) {
            this.givenName = givenName;
            return this;
        }

        public UserBuilder familyName(String familyName) {
            this.familyName = familyName;
            return this;
        }

        public UserBuilder email(String email) {
            this.email = email;
            return this;
        }

        public UserBuilder allGroups(Collection<String> allGroups) {
            this.allGroups = new HashSet<>();
            this.allGroups.addAll(allGroups);
            return this;
        }

        public UserBuilder teskMemberedGroups(Collection<String> teskMemberedGroups) {
            this.teskMemberedGroups = new HashSet<>();
            this.teskMemberedGroups.addAll(teskMemberedGroups);
            return this;
        }

        public UserBuilder teskManagedGroups(Collection<String> teskManagedGroups) {
            this.teskManagedGroups = new HashSet<>();
            this.teskManagedGroups.addAll(teskManagedGroups);
            return this;
        }

        public UserBuilder teskAdmin(boolean teskAdmin) {
            this.teskAdmin = teskAdmin;
            return this;
        }

        public UserBuilder claimAuthorised(boolean claimAuthorised) {
            this.claimAuthorised = claimAuthorised;
            return this;
        }

        public UserBuilder serviceClient(boolean serviceClient) {
            this.serviceClient = serviceClient;
            return this;
        }

        public UserBuilder legacyUserLabels(boolean legacyUserLabels) {
            this.legacyUserLabels = legacyUserLabels;
            return this;
        }

        public User build() {
            return new User(
                    userId,
                    preferredUsername,
                    name,
                    givenName,
                    familyName,
                    email,
                    allGroups,
                    teskMemberedGroups,
                    teskManagedGroups,
                    teskAdmin,
                    claimAuthorised,
                    serviceClient,
                    legacyUserLabels);
        }
    }
}
