package uk.ac.ebi.tsc.tesk.tes.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidTagFilterException;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;

/**
 * The filters of {@code GET /tasks}: the prefix of the task's name, the task's state and its tags.
 * A filter that is left out (null, or empty) matches every task; a task must match all the others.
 */
@Value
public class TaskFilter {

    /** No filter: every task matches. */
    public static final TaskFilter NONE = new TaskFilter(null, null, null);

    /** Prefix of the task's name (case-sensitive); null or empty for any name. */
    String namePrefix;

    /** The task's state; null for any state. */
    TesState state;

    /**
     * Tags the task must have: an empty value matches any value of the key, also an empty one.
     * Never null.
     */
    Map<String, String> tags;

    public TaskFilter(String namePrefix, TesState state, Map<String, String> tags) {
        this.namePrefix = namePrefix;
        this.state = state;
        this.tags =
                tags == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    /** A filter by the prefix of the task's name only. */
    public static TaskFilter byNamePrefix(String namePrefix) {
        return new TaskFilter(namePrefix, null, null);
    }

    /**
     * Zips the {@code tag_key} and {@code tag_value} parameters into the tags of a filter, as the
     * TES specification asks: the n-th value belongs to the n-th key; keys without a value match
     * any value. If a key repeats, its last value counts.
     *
     * @param tagKeys - the keys; null or empty for no tag filter
     * @param tagValues - the values; null or empty for any value of every key
     * @return the tags, in the order of the keys
     * @throws InvalidTagFilterException if there are more values than keys
     */
    public static Map<String, String> zipTags(List<String> tagKeys, List<String> tagValues) {
        int keyCount = tagKeys == null ? 0 : tagKeys.size();
        int valueCount = tagValues == null ? 0 : tagValues.size();
        if (valueCount > keyCount) {
            throw new InvalidTagFilterException();
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < keyCount; i++) {
            String value = i < valueCount ? tagValues.get(i) : null;
            tags.put(tagKeys.get(i), value == null ? "" : value);
        }
        return tags;
    }

    /**
     * Whether a task's name starts with the prefix. Every task matches a null or empty prefix; a
     * task without a name matches no other.
     */
    public boolean matchesName(String name) {
        if (this.namePrefix == null || this.namePrefix.isEmpty()) {
            return true;
        }
        return name != null && name.startsWith(this.namePrefix);
    }

    /** Whether a task in the given state matches; every state matches if no state is set. */
    public boolean matchesState(TesState taskState) {
        return this.state == null || this.state == taskState;
    }

    /**
     * Whether a task with the given tags matches: it must have every key of the filter, with the
     * same value, unless the filter's value is empty. A task without tags matches only if the
     * filter has none.
     */
    public boolean matchesTags(Map<String, String> taskTags) {
        for (Map.Entry<String, String> tag : this.tags.entrySet()) {
            if (taskTags == null || !taskTags.containsKey(tag.getKey())) {
                return false;
            }
            if (!tag.getValue().isEmpty() && !tag.getValue().equals(taskTags.get(tag.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the filter needs the tasks' tags. */
    public boolean hasTags() {
        return !this.tags.isEmpty();
    }
}
