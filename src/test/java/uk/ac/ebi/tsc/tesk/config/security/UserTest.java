package uk.ac.ebi.tsc.tesk.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_USERID_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_USERHASH_KEY;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;

/**
 * {@link User} without the group model (issue #225): a caller is its ID, and owns exactly the tasks
 * stamped with that ID
 */
public class UserTest {

    private static final String HASH_123 = "a665a45920422f9d417e4867efdc4fb8";

    private static V1ObjectMeta createdBy(String userId) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        CreatorLabels.put(metadata, userId);
        return metadata;
    }

    @Test
    public void account_hasNoPasswordNoAuthoritiesAndIsAlwaysUsable() {
        User user = User.builder("123").build();
        assertNull(user.getPassword());
        assertTrue(user.getAuthorities().isEmpty());
        assertTrue(user.isAccountNonExpired());
        assertTrue(user.isAccountNonLocked());
        assertTrue(user.isCredentialsNonExpired());
        assertTrue(user.isEnabled());
        assertFalse(user.isServiceClient());
    }

    @Test
    public void usernameAndName_areTheFullId() {
        User user = User.builder("a@b|c").build();
        assertEquals("a@b|c", user.getUsername());
        assertEquals("a@b|c", user.getName());
    }

    /** Every caller lists by the hash label of its own ID, whatever kind of caller it is */
    @Test
    public void labelSelector_isTheHashOfTheId() {
        assertEquals(
                "creator-user-hash=" + HASH_123, User.builder("123").build().getLabelSelector());
        assertEquals(
                "creator-user-hash=" + HASH_123,
                User.builder("123").serviceClient(true).build().getLabelSelector());
    }

    @Test
    public void isCreatorOf_exactIdOnly() {
        assertTrue(User.builder("a@b").build().isCreatorOf(createdBy("a@b")));
        assertFalse(User.builder("a_b").build().isCreatorOf(createdBy("a@b")));
        assertFalse(User.builder("a@b").build().isCreatorOf(createdBy("a_b")));
        assertFalse(User.builder("a@b").build().isCreatorOf(createdBy("a@b ")));
    }

    @Test
    public void isCreatorOf_annotationMustMatchHash() {
        V1ObjectMeta metadata =
                new V1ObjectMeta()
                        .putLabelsItem(LABEL_USERHASH_KEY, CreatorLabels.hash("a@b"))
                        .putAnnotationsItem(ANN_USERID_KEY, "a_b");
        assertFalse(User.builder("a_b").build().isCreatorOf(metadata));
        assertFalse(User.builder("a@b").build().isCreatorOf(metadata));
    }

    /**
     * Objects without the hash label and the annotation (e.g. of tasks created before them, with
     * the old label only) have no creator: nobody owns them
     */
    @Test
    public void isCreatorOf_objectsWithoutCreatorAreNobodys() {
        User user = User.builder("a_b").build();
        assertFalse(user.isCreatorOf(null));
        assertFalse(user.isCreatorOf(new V1ObjectMeta()));
        assertFalse(user.isCreatorOf(new V1ObjectMeta().putLabelsItem("creator-user-id", "a_b")));
        assertFalse(
                user.isCreatorOf(
                        new V1ObjectMeta()
                                .putLabelsItem(LABEL_USERHASH_KEY, CreatorLabels.hash("a_b"))));
        assertFalse(user.isCreatorOf(new V1ObjectMeta().putAnnotationsItem(ANN_USERID_KEY, "a_b")));
    }

    @Test
    public void toString_namesIdAndKind() {
        assertEquals(
                "User{userId='c', serviceClient=true}",
                User.builder("c").serviceClient(true).build().toString());
    }

    @Test
    public void equals_sameIdAndKind() {
        assertEquals(User.builder("a").build(), User.builder("a").build());
        assertEquals(User.builder("a").build().hashCode(), User.builder("a").build().hashCode());
        assertNotEquals(User.builder("a").build(), User.builder("b").build());
        assertNotEquals(User.builder("a").build(), User.builder("a").serviceClient(true).build());
        assertNotEquals(User.builder("a").build(), null);
        assertNotEquals(User.builder("a").build(), "a");
        User user = User.builder("a").build();
        assertEquals(user, user);
    }
}
