package uk.ac.ebi.tsc.tesk.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.Test;

/** The account, group and equality rules of {@link User} */
public class UserTest {

    private static User.UserBuilder full() {
        return full("user-1");
    }

    /** A user with all data */
    private static User.UserBuilder full(String userId) {
        return User.builder(userId)
                .preferredUsername("preferred")
                .name("Name")
                .givenName("Given")
                .familyName("Family")
                .email("user@example.org")
                .allGroups(Collections.singleton("elixir:GA4GH:GA4GH-CAP:EBI:TEST"))
                .teskMemberedGroups(Collections.singleton("TEST"))
                .teskManagedGroups(Collections.singleton("MANAGED"));
    }

    /** Users authenticate with tokens: no password, and the account is always usable */
    @Test
    public void account_hasNoPasswordAndIsAlwaysUsable() {
        User user = User.builder("user-1").build();
        assertThat(user.getPassword()).isNull();
        assertThat(user.isAccountNonExpired()).isTrue();
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.isCredentialsNonExpired()).isTrue();
        assertThat(user.isEnabled()).isTrue();
    }

    /** A user without groups is neither member nor manager, and has no group for new tasks */
    @Test
    public void withoutGroups() {
        User user = User.builder("user-1").build();
        assertThat(user.isMember()).isFalse();
        assertThat(user.isManager()).isFalse();
        assertThat(user.isGroupMember("TEST")).isFalse();
        assertThat(user.isGroupManager("TEST")).isFalse();
        assertThat(user.getAnyGroup()).isNull();
        assertThat(user.getAuthorities()).isEmpty();
    }

    /** A member's group is the group of the tasks it creates without one */
    @Test
    public void member_anyGroupIsTheirGroup() {
        User user =
                User.builder("user-1").teskMemberedGroups(Collections.singleton("TEST")).build();
        assertThat(user.isMember()).isTrue();
        assertThat(user.isGroupMember("TEST")).isTrue();
        assertThat(user.isGroupMember("OTHER")).isFalse();
        assertThat(user.getAnyGroup()).isEqualTo("TEST");
    }

    /** A manager of groups only, without membership, lists the tasks of the managed groups */
    @Test
    public void managerOnly_labelSelectorNamesManagedGroups() {
        User user =
                User.builder("user-1").teskManagedGroups(Collections.singleton("MANAGED")).build();
        assertThat(user.getLabelSelector()).isEqualTo("creator-group-name in (MANAGED)");
    }

    /** The log of the user lists the user's data, but no credentials (there are none) */
    @Test
    public void toString_listsTheUser() {
        assertThat(full().teskAdmin(true).build().toString())
                .isEqualTo(
                        "User{userId='user-1', preferredUsername='preferred', name='Name',"
                                + " givenName='Given', familyName='Family',"
                                + " email='user@example.org',"
                                + " allGroups=(elixir:GA4GH:GA4GH-CAP:EBI:TEST),"
                                + " memberedGroups=(TEST), managedGroups=(MANAGED), iasAdmin=true,"
                                + " claimAuthorised=false, serviceClient=false,"
                                + " legacyUserLabels=false}");
    }

    /** Users with the same data are equal, and have the same hash code */
    @Test
    public void equals_sameData() {
        User user = full().build();
        assertThat(user).isEqualTo(user);
        assertThat(user).isEqualTo(full().build());
        assertThat(user.hashCode()).isEqualTo(full().build().hashCode());
        assertThat(user).isNotEqualTo(null);
        assertThat(user).isNotEqualTo("user-1");
    }

    /** Users that differ in any one property aren't equal */
    @Test
    public void equals_anyDifferentProperty() {
        List<UnaryOperator<User.UserBuilder>> changes =
                Arrays.asList(
                        b -> b.teskAdmin(true),
                        b -> b.claimAuthorised(true),
                        b -> b.serviceClient(true),
                        b -> b.legacyUserLabels(true),
                        b -> full("user-2"),
                        b -> b.preferredUsername("other"),
                        b -> b.name("Other"),
                        b -> b.givenName("Other"),
                        b -> b.familyName("Other"),
                        b -> b.email("other@example.org"),
                        b -> b.allGroups(Collections.emptySet()),
                        b -> b.teskMemberedGroups(Collections.emptySet()),
                        b -> b.teskManagedGroups(Collections.emptySet()));
        User user = full().build();
        for (UnaryOperator<User.UserBuilder> change : changes) {
            assertThat(change.apply(full()).build()).isNotEqualTo(user);
        }
    }
}
