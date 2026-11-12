package uk.ac.ebi.tsc.tesk.tes.service;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
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
     * Tags (key and value) the task must all have: an empty value matches any value of the key,
     * also an empty one. Never null.
     */
    List<Map.Entry<String, String>> tags;

    public TaskFilter(String namePrefix, TesState state, List<Map.Entry<String, String>> tags) {
        this.namePrefix = namePrefix;
        this.state = state;
        this.tags =
                tags == null
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(new ArrayList<>(tags));
    }

    /** A filter by the prefix of the task's name only. */
    public static TaskFilter byNamePrefix(String namePrefix) {
        return new TaskFilter(namePrefix, null, null);
    }

    /** A tag of a filter. */
    public static Map.Entry<String, String> tag(String key, String value) {
        return new SimpleImmutableEntry<>(key, value);
    }

    /**
     * Zips the {@code tag_key} and {@code tag_value} parameters into the tags of a filter, as the
     * TES specification asks: the n-th value belongs to the n-th key; keys without a value match
     * any value. A repeated key is one more tag the task must have, so {@code foo=bar} and {@code
     * foo=baz} match no task. Empty keys filter nothing (as an empty {@code name_prefix} or {@code
     * state}), and neither do empty values beyond the keys.
     *
     * @param tagKeys - the keys; null or empty for no tag filter
     * @param tagValues - the values; null or empty for any value of every key
     * @return the tags, in the order of the keys
     * @throws InvalidTagFilterException if there are more values than keys, other than empty ones
     */
    public static List<Map.Entry<String, String>> zipTags(
            List<String> tagKeys, List<String> tagValues) {
        List<String> keys = tagKeys == null ? Collections.emptyList() : tagKeys;
        List<String> values = tagValues == null ? Collections.emptyList() : tagValues;
        for (int i = keys.size(); i < values.size(); i++) {
            if (values.get(i) != null && !values.get(i).isEmpty()) {
                throw new InvalidTagFilterException();
            }
        }
        List<Map.Entry<String, String>> tags = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (key == null || key.isEmpty()) {
                continue;
            }
            String value = i < values.size() ? values.get(i) : null;
            tags.add(tag(key, value == null ? "" : value));
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
        for (Map.Entry<String, String> tag : this.tags) {
            if (taskTags == null || !taskTags.containsKey(tag.getKey())) {
                return false;
            }
            if (!tag.getValue().isEmpty() && !tag.getValue().equals(taskTags.get(tag.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the filter needs the tasks' states. */
    public boolean hasState() {
        return this.state != null;
    }

    /** Whether the filter needs the tasks' tags. */
    public boolean hasTags() {
        return !this.tags.isEmpty();
    }
}
