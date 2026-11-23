package uk.ac.ebi.tsc.tesk.k8s.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_GROUPNAME_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_GROUPNAME_KEY;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The team's label and annotation of a task's Kubernetes objects (issue #216) */
public class GroupLabelsTest {

    /** Valid label values: alphanumeric at both ends, {@code -_.} in between */
    private static final Pattern LABEL_VALUE =
            Pattern.compile("^(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?$");

    /** Team names that aren't valid label values, e.g. from LS Login (percent-encoded spaces) */
    static final String[] AWKWARD_NAMES = {
        "BioHackCloud%20Admins",
        "BioHackCloud Admins",
        "ünïcödé",
        "-leading",
        "trailing_",
        "a,b",
        "x) ,creator-user-hash in (y",
        "!",
        "",
        "Team.With.A.Rather.Long.Name.That.Goes.On.And.On.Beyond.Sixty-Three.Characters",
    };

    @Test
    public void value_validNamesStayAsTheyAre() {
        StringBuilder longest = new StringBuilder();
        for (int i = 0; i < GroupLabels.MAX_LENGTH; i++) {
            longest.append("x");
        }
        for (String name :
                new String[] {"TEST", "EOC-Compute", "a.b_c-d", "1", longest.toString()}) {
            assertEquals(name, GroupLabels.value(name));
        }
    }

    @Test
    public void value_validLabelValueForAnyName() {
        Set<String> values = new HashSet<>();
        for (String name : AWKWARD_NAMES) {
            String value = GroupLabels.value(name);
            assertTrue(LABEL_VALUE.matcher(value).matches(), value);
            assertTrue(value.length() <= GroupLabels.MAX_LENGTH, value);
            assertTrue(value.startsWith(GroupLabels.HASH_PREFIX), value);
            assertEquals(value, GroupLabels.value(name));
            values.add(value);
        }
        assertEquals(AWKWARD_NAMES.length, values.size());
    }

    /** A valid name of 64 characters is one too long */
    @Test
    public void value_hashOfNamesLongerThan63Characters() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i <= GroupLabels.MAX_LENGTH; i++) {
            name.append("x");
        }
        assertEquals(
                GroupLabels.HASH_PREFIX + CreatorLabels.hash(name.toString()),
                GroupLabels.value(name.toString()));
    }

    /**
     * A team named like the label value of another team gets the same label, but not the same
     * group: the annotation tells them apart
     */
    @Test
    public void groupName_teamNamedLikeAHashLabel() {
        String hashed = GroupLabels.value("a b");
        assertEquals(hashed, GroupLabels.value(hashed));
        V1ObjectMeta ofHashed = new V1ObjectMeta();
        GroupLabels.put(ofHashed, hashed);
        V1ObjectMeta ofOther = new V1ObjectMeta();
        GroupLabels.put(ofOther, "a b");
        assertEquals(hashed, GroupLabels.groupName(ofHashed));
        assertEquals("a b", GroupLabels.groupName(ofOther));
    }

    @Test
    public void value_hashOfTheName() {
        assertEquals(
                GroupLabels.HASH_PREFIX + CreatorLabels.hash("BioHackCloud%20Admins"),
                GroupLabels.value("BioHackCloud%20Admins"));
        assertNotEquals(GroupLabels.value("a b"), GroupLabels.value("a%20b"));
    }

    @Test
    public void put_setsLabelAndAnnotation() {
        for (String name : AWKWARD_NAMES) {
            V1ObjectMeta metadata = new V1ObjectMeta();
            GroupLabels.put(metadata, name);
            assertEquals(GroupLabels.value(name), metadata.getLabels().get(LABEL_GROUPNAME_KEY));
            assertEquals(name, metadata.getAnnotations().get(ANN_GROUPNAME_KEY));
            assertEquals(name, GroupLabels.groupName(metadata));
        }
    }

    @Test
    public void put_nullIsNoGroup() {
        V1ObjectMeta metadata = new V1ObjectMeta();
        GroupLabels.put(metadata, null);
        assertTrue(metadata.getLabels() == null || metadata.getLabels().isEmpty());
        assertTrue(metadata.getAnnotations() == null || metadata.getAnnotations().isEmpty());
        assertNull(GroupLabels.groupName(metadata));
    }

    /** Tasks created before the annotation have only the label, with the name as it is */
    @Test
    public void groupName_labelWithoutAnnotation() {
        V1ObjectMeta metadata = new V1ObjectMeta().putLabelsItem(LABEL_GROUPNAME_KEY, "TEST");
        assertEquals("TEST", GroupLabels.groupName(metadata));
    }

    /** An annotation that the label doesn't match names no other team than the label does */
    @Test
    public void groupName_labelIfTheAnnotationDoesNotMatch() {
        V1ObjectMeta metadata =
                new V1ObjectMeta()
                        .putLabelsItem(LABEL_GROUPNAME_KEY, "TEST")
                        .putAnnotationsItem(ANN_GROUPNAME_KEY, "OTHER");
        assertEquals("TEST", GroupLabels.groupName(metadata));
    }

    /** Without the label, an object has no team, whatever the annotation says */
    @Test
    public void groupName_nullWithoutLabel() {
        assertNull(GroupLabels.groupName(new V1ObjectMeta()));
        assertNull(
                GroupLabels.groupName(
                        new V1ObjectMeta().putAnnotationsItem(ANN_GROUPNAME_KEY, "TEST")));
    }
}
