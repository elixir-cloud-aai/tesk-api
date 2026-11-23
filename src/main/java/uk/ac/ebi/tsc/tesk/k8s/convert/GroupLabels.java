package uk.ac.ebi.tsc.tesk.k8s.convert;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_GROUPNAME_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_GROUPNAME_KEY;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The team (group) of a task's Kubernetes objects: a label, by which Kubernetes selects the tasks
 * of teams, and an annotation with the full team name. Team names can be any text without {@code :}
 * (e.g. {@code BioHackCloud%20Admins} from LS Login), while label values are limited to 63
 * characters from {@code [A-Za-z0-9._-]}, alphanumeric at both ends; annotations take any string.
 *
 * <p>A name that is a valid label value is the label's value, as for tasks created before the
 * annotation, which have only the label; any other name is labelled with a hash of it ({@link
 * #value}). The team of an object is the annotation if the label matches it, otherwise the label
 * ({@link #groupName}).
 */
public final class GroupLabels {

    /** Longest label value that Kubernetes accepts */
    public static final int MAX_LENGTH = 63;

    /** Start of the label value of names that aren't valid label values, before their hash */
    public static final String HASH_PREFIX = "sha256-";

    /** Valid label values (also the empty one, which isn't used, see {@link #value}) */
    private static final Pattern LABEL_VALUE =
            Pattern.compile("(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?");

    private GroupLabels() {}

    /**
     * The label value for a team name: the name itself if it is a non-empty valid label value,
     * otherwise {@link #HASH_PREFIX} and the start of the name's SHA-256 (as for user IDs, see
     * {@link CreatorLabels#hash}); label selectors must use the same value
     */
    public static String value(String groupName) {
        if (!groupName.isEmpty()
                && groupName.length() <= MAX_LENGTH
                && LABEL_VALUE.matcher(groupName).matches()) {
            return groupName;
        }
        return HASH_PREFIX + CreatorLabels.hash(groupName);
    }

    /** Labels and annotates the object as one of the team; no team (null) adds neither */
    public static void put(V1ObjectMeta metadata, String groupName) {
        if (groupName == null) {
            return;
        }
        metadata.putLabelsItem(LABEL_GROUPNAME_KEY, value(groupName));
        metadata.putAnnotationsItem(ANN_GROUPNAME_KEY, groupName);
    }

    /**
     * The team of the object: null without the label; the annotation if the label is its value;
     * otherwise the label (objects created before the annotation, whose label is the name)
     */
    public static String groupName(V1ObjectMeta metadata) {
        String label = get(metadata.getLabels(), LABEL_GROUPNAME_KEY);
        if (label == null) {
            return null;
        }
        String annotation = get(metadata.getAnnotations(), ANN_GROUPNAME_KEY);
        if (annotation != null && value(annotation).equals(label)) {
            return annotation;
        }
        return label;
    }

    private static String get(Map<String, String> map, String key) {
        return map == null ? null : map.get(key);
    }
}
