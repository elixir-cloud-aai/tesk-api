package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_USERID_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_LEGACY_USERID_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_USERHASH_KEY;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.regex.Pattern;
import org.junit.Test;

/** The creator's label and annotation of a task's Kubernetes objects (issue #119) */
public class CreatorLabelsTest {

    /** Valid label values: at most 63 characters, alphanumeric at both ends */
    private static final Pattern LABEL_VALUE =
            Pattern.compile("^(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?$");

    @Test
    public void hash_truncatedSha256InHex() {
        assertEquals("a665a45920422f9d417e4867efdc4fb8", CreatorLabels.hash("123"));
        assertEquals("d159ef624ed86697b4f1f3ff086aacdd", CreatorLabels.hash("user@example.org"));
    }

    @Test
    public void hash_distinguishesIdsThatTheOldEncodingMerged() {
        assertEquals(CreatorLabels.legacyValue("a@b"), CreatorLabels.legacyValue("a_b"));
        assertNotEquals(CreatorLabels.hash("a@b"), CreatorLabels.hash("a_b"));
    }

    @Test
    public void hash_validLabelValueForAnyId() {
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longId.append("x");
        }
        for (String id :
                new String[] {
                    "123", "user@example.org", "a|b c", "ünïcödé", "-leading", longId.toString(), ""
                }) {
            String hash = CreatorLabels.hash(id);
            assertEquals(CreatorLabels.HASH_LENGTH, hash.length());
            assertTrue(hash, LABEL_VALUE.matcher(hash).matches());
        }
    }

    @Test
    public void put_setsHashLabelAndIdAnnotation() {
        V1ObjectMeta metadata = new V1ObjectMeta();
        CreatorLabels.put(metadata, "user@example.org");
        assertEquals(
                "d159ef624ed86697b4f1f3ff086aacdd", metadata.getLabels().get(LABEL_USERHASH_KEY));
        assertEquals("user@example.org", metadata.getAnnotations().get(ANN_USERID_KEY));
        assertFalse(metadata.getLabels().containsKey(LABEL_LEGACY_USERID_KEY));
        assertFalse(CreatorLabels.isLegacy(metadata));
        assertEquals("user@example.org", CreatorLabels.creatorId(metadata));
    }

    @Test
    public void creatorId_nullIfAnnotationDoesNotMatchHash() {
        V1ObjectMeta metadata =
                new V1ObjectMeta()
                        .putLabelsItem(LABEL_USERHASH_KEY, CreatorLabels.hash("a@b"))
                        .putAnnotationsItem(ANN_USERID_KEY, "a_b");
        assertNull(CreatorLabels.creatorId(metadata));
    }

    @Test
    public void creatorId_nullWithoutAnnotation() {
        V1ObjectMeta metadata =
                new V1ObjectMeta().putLabelsItem(LABEL_USERHASH_KEY, CreatorLabels.hash("1"));
        assertNull(CreatorLabels.creatorId(metadata));
    }

    @Test
    public void legacy_objectsWithOnlyTheOldLabel() {
        V1ObjectMeta metadata = new V1ObjectMeta().putLabelsItem(LABEL_LEGACY_USERID_KEY, "a_b");
        assertTrue(CreatorLabels.isLegacy(metadata));
        assertNull(CreatorLabels.creatorId(metadata));
        assertEquals("a_b", CreatorLabels.legacyCreator(metadata));
        assertTrue(CreatorLabels.isLegacy(new V1ObjectMeta()));
        assertNull(CreatorLabels.legacyCreator(new V1ObjectMeta()));
        assertNull(CreatorLabels.creatorId(new V1ObjectMeta()));
    }
}
