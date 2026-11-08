package uk.ac.ebi.tsc.tesk.config.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_USERID_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_USERHASH_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_USERID_KEY;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.Collections;
import org.junit.Test;
import uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels;
import uk.ac.ebi.tsc.tesk.tes.model.TesTaskLog;

/**
 * How {@link User} recognises the tasks it created, by the hash label and the annotation, and by
 * the old label of tasks created before (issue #119)
 */
public class UserCreatorTest {

    private static final String HASH_123 = "a665a45920422f9d417e4867efdc4fb8";

    private static V1ObjectMeta createdBy(String userId) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        CreatorLabels.put(metadata, userId);
        return metadata;
    }

    private static V1ObjectMeta legacyLabel(String value) {
        return new V1ObjectMeta().putLabelsItem(LABEL_USERID_KEY, value);
    }

    private static TesTaskLog log(String userId, boolean legacy) {
        TesTaskLog log = new TesTaskLog();
        if (userId != null) {
            log.putMetadataItem("USER_ID", userId);
        }
        log.setUserIdFromLegacyLabel(legacy);
        return log;
    }

    private static User user(String userId, boolean legacyUserLabels) {
        return User.builder(userId).legacyUserLabels(legacyUserLabels).build();
    }

    @Test
    public void username_isTheFullId() {
        assertEquals("user@example.org", User.builder("user@example.org").build().getUsername());
    }

    @Test
    public void labelSelector_memberSelectsByHash() {
        User user = User.builder("123").teskMemberedGroups(Collections.singleton("TEST")).build();
        assertEquals(
                "creator-group-name in (TEST),creator-user-hash=" + HASH_123,
                user.getLabelSelector());
    }

    @Test
    public void labelSelector_claimUserSelectsByHash() {
        User user = User.builder("123").claimAuthorised(true).build();
        assertEquals(
                "creator-user-hash=" + HASH_123 + ",!creator-group-name", user.getLabelSelector());
    }

    /** With the old labels, the creator can't be selected by Kubernetes, only filtered after */
    @Test
    public void labelSelector_legacyUserLabels() {
        User member =
                User.builder("123")
                        .teskMemberedGroups(Collections.singleton("TEST"))
                        .legacyUserLabels(true)
                        .build();
        assertEquals("creator-group-name in (TEST)", member.getLabelSelector());
        User claimUser = User.builder("123").claimAuthorised(true).legacyUserLabels(true).build();
        assertEquals("!creator-group-name", claimUser.getLabelSelector());
        User admin = User.builder("123").teskAdmin(true).legacyUserLabels(true).build();
        assertNull(admin.getLabelSelector());
    }

    @Test
    public void isCreatorOf_exactIdOnly() {
        assertTrue(user("a@b", false).isCreatorOf(createdBy("a@b")));
        assertFalse(user("a_b", false).isCreatorOf(createdBy("a@b")));
        assertFalse(user("a@b", false).isCreatorOf(createdBy("a_b")));
        assertFalse(user("a@b", true).isCreatorOf(createdBy("a_b")));
    }

    @Test
    public void isCreatorOf_annotationMustMatchHash() {
        V1ObjectMeta metadata =
                new V1ObjectMeta()
                        .putLabelsItem(LABEL_USERHASH_KEY, CreatorLabels.hash("a@b"))
                        .putAnnotationsItem(ANN_USERID_KEY, "a_b");
        assertFalse(user("a_b", false).isCreatorOf(metadata));
        assertFalse(user("a@b", false).isCreatorOf(metadata));
    }

    @Test
    public void isCreatorOf_oldLabelOnlyWithTheSetting() {
        assertFalse(user("a@b", false).isCreatorOf(legacyLabel("a_b")));
        assertTrue(user("a@b", true).isCreatorOf(legacyLabel("a_b")));
        assertFalse(user("a@c", true).isCreatorOf(legacyLabel("a_b")));
        assertFalse(user("a@b", true).isCreatorOf(new V1ObjectMeta()));
    }

    @Test
    public void isCreator_taskLog() {
        assertTrue(user("a@b", false).isCreator(log("a@b", false)));
        assertFalse(user("a_b", false).isCreator(log("a@b", false)));
        assertFalse(user("a@b", true).isCreator(log("a_b", false)));
        assertFalse(user("a@b", false).isCreator(log("a_b", true)));
        assertTrue(user("a@b", true).isCreator(log("a_b", true)));
        assertFalse(user("a@b", true).isCreator(log(null, true)));
        assertFalse(user("a@b", true).isCreator(log(null, false)));
        assertFalse(user("a@b", true).isCreator(null));
        TesTaskLog noMetadata = new TesTaskLog();
        assertFalse(user("a@b", true).isCreator(noMetadata));
    }

    @Test
    public void legacyUserLabels_partOfEqualsAndToString() {
        User user = user("123", true);
        assertEquals(user, user("123", true));
        assertEquals(user.hashCode(), user("123", true).hashCode());
        assertNotEquals(user, user("123", false));
        assertTrue(user.isLegacyUserLabels());
        assertTrue(user.toString().contains("legacyUserLabels=true"));
    }
}
