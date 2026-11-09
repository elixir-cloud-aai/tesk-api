package uk.ac.ebi.tsc.tesk.k8s.convert;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_USERID_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_LEGACY_USERID_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_USERHASH_KEY;

import com.google.common.hash.Hashing;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The creating user of a task's Kubernetes objects: a label with a hash of the user's ID, by which
 * Kubernetes selects the user's tasks, and an annotation with the full ID, which is compared
 * exactly. User IDs (the OIDC {@code sub}) can be any string, while label values are limited to 63
 * characters from {@code [A-Za-z0-9._-]}; annotations take any string.
 *
 * <p>Tasks created before had only the label {@code creator-user-id}, with the ID's {@code @}
 * replaced by {@code _} ({@link #legacyValue}), an encoding that gives different IDs the same value
 * and doesn't fit all IDs.
 */
public final class CreatorLabels {

    /** Length of the hash label's value: the first 128 bits of SHA-256, in hexadecimal digits */
    public static final int HASH_LENGTH = 32;

    private CreatorLabels() {}

    /** The value of the hash label for a user ID: the start of its SHA-256 (of UTF-8), in hex */
    public static String hash(String userId) {
        return Hashing.sha256()
                .hashString(userId, StandardCharsets.UTF_8)
                .toString()
                .substring(0, HASH_LENGTH);
    }

    /** The value of the old label for a user ID */
    public static String legacyValue(String userId) {
        return userId.replace("@", "_");
    }

    /** Labels and annotates the object as created by the user */
    public static void put(V1ObjectMeta metadata, String userId) {
        metadata.putLabelsItem(LABEL_USERHASH_KEY, hash(userId));
        metadata.putAnnotationsItem(ANN_USERID_KEY, userId);
    }

    /** Whether the object was created before the hash label: it has none */
    public static boolean isLegacy(V1ObjectMeta metadata) {
        return get(metadata.getLabels(), LABEL_USERHASH_KEY) == null;
    }

    /**
     * The ID of the user who created the object: the annotation, if the hash label matches it;
     * otherwise (also for objects created before the hash label) null
     */
    public static String creatorId(V1ObjectMeta metadata) {
        String hash = get(metadata.getLabels(), LABEL_USERHASH_KEY);
        String userId = get(metadata.getAnnotations(), ANN_USERID_KEY);
        if (hash == null || userId == null || !hash.equals(hash(userId))) {
            return null;
        }
        return userId;
    }

    /** The value of the old label (of objects created before the hash label), or null */
    public static String legacyCreator(V1ObjectMeta metadata) {
        return get(metadata.getLabels(), LABEL_LEGACY_USERID_KEY);
    }

    private static String get(Map<String, String> map, String key) {
        return map == null ? null : map.get(key);
    }
}
