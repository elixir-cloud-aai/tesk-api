package uk.ac.ebi.tsc.tesk.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;
import uk.ac.ebi.tsc.tesk.k8s.convert.GroupLabels;

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

    /** By default (as without authentication), group rules apply, not owner-only visibility */
    @Test
    public void ownerOnly_offByDefault() {
        assertThat(User.builder("user-1").build().isOwnerOnly()).isFalse();
    }

    /**
     * With owner-only visibility (issue #218), a super admin lists only the own tasks, of any group
     * or none
     */
    @Test
    public void ownerOnly_adminLabelSelectorNamesTheCreator() {
        User admin = User.builder("123").teskAdmin(true).ownerOnly(true).build();
        assertThat(admin.getLabelSelector())
                .isEqualTo("creator-user-hash=a665a45920422f9d417e4867efdc4fb8");
        assertThat(admin.isListFiltered()).isTrue();
    }

    /** With group visibility, a super admin lists all tasks: no selector and no filter */
    @Test
    public void groupVisibility_adminListsAllTasks() {
        User admin = User.builder("123").teskAdmin(true).build();
        assertThat(admin.getLabelSelector()).isNull();
        assertThat(admin.isListFiltered()).isFalse();
    }

    /**
     * With owner-only visibility and the old labels, the selector can't name the creator: no
     * selector for a super admin, but the list is still filtered by the creator
     */
    @Test
    public void ownerOnly_adminWithLegacyLabels_filtersWithoutSelector() {
        User admin =
                User.builder("123").teskAdmin(true).ownerOnly(true).legacyUserLabels(true).build();
        assertThat(admin.getLabelSelector()).isNull();
        assertThat(admin.isListFiltered()).isTrue();
    }

    /** With owner-only visibility, a team admin lists only the own tasks of the teams */
    @Test
    public void ownerOnly_managerLabelSelectorNamesTheCreator() {
        User manager =
                User.builder("123")
                        .teskManagedGroups(Collections.singleton("MANAGED"))
                        .ownerOnly(true)
                        .build();
        assertThat(manager.getLabelSelector())
                .isEqualTo(
                        "creator-group-name in (MANAGED),"
                                + "creator-user-hash=a665a45920422f9d417e4867efdc4fb8");
    }

    /**
     * With owner-only visibility, the list keeps only the own tasks, also of managed teams; with
     * group visibility, also the other tasks of managed teams
     */
    @Test
    public void isListed_ownerOnlyKeepsOwnTasksOnly() {
        V1ObjectMeta own = new V1ObjectMeta().name("task-1");
        CreatorLabels.put(own, "123");
        GroupLabels.put(own, "MANAGED");
        V1ObjectMeta others = new V1ObjectMeta().name("task-2");
        CreatorLabels.put(others, "124");
        GroupLabels.put(others, "MANAGED");
        User.UserBuilder manager =
                User.builder("123").teskManagedGroups(Collections.singleton("MANAGED"));

        User ownerOnly = manager.ownerOnly(true).build();
        assertThat(ownerOnly.isListed(own)).isTrue();
        assertThat(ownerOnly.isListed(others)).isFalse();

        User group = manager.ownerOnly(false).build();
        assertThat(group.isListed(own)).isTrue();
        assertThat(group.isListed(others)).isTrue();
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
                                + " legacyUserLabels=false, ownerOnly=false}");
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
                        b -> b.ownerOnly(true),
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
