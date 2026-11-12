package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.ac.ebi.tsc.tesk.tes.service.TaskFilter.tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidTagFilterException;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;

/** The filters of {@code GET /tasks} (issue #143). */
class TaskFilterTest {

    /** Tags of a filter: key, value, key, value, ... */
    static List<Map.Entry<String, String>> filterTags(String... keysAndValues) {
        List<Map.Entry<String, String>> tags = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            tags.add(tag(keysAndValues[i], keysAndValues[i + 1]));
        }
        return tags;
    }

    /** Tags of a task: key, value, key, value, ... */
    private static Map<String, String> taskTags(String... keysAndValues) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            tags.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tags;
    }

    private static boolean matches(
            List<Map.Entry<String, String>> filterTags, Map<String, String> taskTags) {
        return new TaskFilter(null, null, filterTags).matchesTags(taskTags);
    }

    /** The table of tag filters and task tags of the TES 1.1 specification ({@code tag_key}). */
    @Test
    void tagsMatchAsInTheSpecification() {
        assertThat(matches(filterTags("foo", "bar"), taskTags("foo", "bar"))).isTrue();
        assertThat(matches(filterTags("foo", "bar"), taskTags("foo", "bat"))).isFalse();
        assertThat(matches(filterTags("foo", ""), taskTags("foo", ""))).isTrue();
        assertThat(
                        matches(
                                filterTags("foo", "bar", "baz", "bat"),
                                taskTags("foo", "bar", "baz", "bat")))
                .isTrue();
        assertThat(matches(filterTags("foo", "bar"), taskTags("foo", "bar", "baz", "bat")))
                .isTrue();
        assertThat(matches(filterTags("foo", "bar", "baz", "bat"), taskTags("foo", "bar")))
                .isFalse();
        assertThat(matches(filterTags("foo", ""), taskTags("foo", "bar"))).isTrue();
        assertThat(matches(filterTags("foo", ""), taskTags())).isFalse();
    }

    /** A task without tags matches only a filter without tags. */
    @Test
    void taskWithoutTags() {
        assertThat(matches(filterTags(), null)).isTrue();
        assertThat(matches(filterTags("foo", ""), null)).isFalse();
        assertThat(TaskFilter.NONE.matchesTags(null)).isTrue();
    }

    /**
     * Values are compared exactly: case-sensitive; a tag whose value is null matches only an empty
     * filter value.
     */
    @Test
    void tagValuesAreComparedExactly() {
        assertThat(matches(filterTags("foo", "bar"), taskTags("foo", "Bar"))).isFalse();
        assertThat(matches(filterTags("foo", "bar"), taskTags("foo", null))).isFalse();
        assertThat(matches(filterTags("foo", ""), taskTags("foo", null))).isTrue();
    }

    /** Every tag of the filter must match, also those of a repeated key. */
    @Test
    void repeatedKeyMustMatchEveryValue() {
        assertThat(matches(filterTags("foo", "bar", "foo", "baz"), taskTags("foo", "bar")))
                .isFalse();
        assertThat(matches(filterTags("foo", "bar", "foo", ""), taskTags("foo", "bar"))).isTrue();
        assertThat(matches(filterTags("foo", "bar", "foo", ""), taskTags("foo", "baz"))).isFalse();
    }

    /** Values belong to the keys in order; keys without one match any value. */
    @Test
    void zipTagsPairsKeysWithValues() {
        assertThat(
                        TaskFilter.zipTags(
                                Arrays.asList("WORKFLOW_ID", "PROJECT"),
                                Collections.singletonList("cwl-01234")))
                .containsExactly(tag("WORKFLOW_ID", "cwl-01234"), tag("PROJECT", ""));
        assertThat(TaskFilter.zipTags(Collections.singletonList("foo"), null))
                .containsExactly(tag("foo", ""));
        assertThat(TaskFilter.zipTags(null, null)).isEmpty();
    }

    /** A repeated key stays a tag of its own. */
    @Test
    void zipTagsKeepsRepeatedKeys() {
        assertThat(TaskFilter.zipTags(Arrays.asList("foo", "foo"), Arrays.asList("bar", "baz")))
                .containsExactly(tag("foo", "bar"), tag("foo", "baz"));
        assertThat(
                        TaskFilter.zipTags(
                                Arrays.asList("foo", "foo"), Collections.singletonList("bar")))
                .containsExactly(tag("foo", "bar"), tag("foo", ""));
    }

    /**
     * Empty keys filter nothing, like an empty name_prefix or state, and neither do empty values
     * beyond the keys.
     */
    @Test
    void zipTagsIgnoresEmptyParameters() {
        assertThat(TaskFilter.zipTags(Collections.singletonList(""), null)).isEmpty();
        assertThat(TaskFilter.zipTags(Arrays.asList("", "foo"), Arrays.asList("ignored", "bar")))
                .containsExactly(tag("foo", "bar"));
        assertThat(TaskFilter.zipTags(null, Collections.singletonList(""))).isEmpty();
        assertThat(TaskFilter.zipTags(Collections.singletonList("foo"), Arrays.asList("bar", "")))
                .containsExactly(tag("foo", "bar"));
    }

    /** More values than keys are refused. */
    @Test
    void zipTagsRefusesMoreValuesThanKeys() {
        assertThatThrownBy(
                        () ->
                                TaskFilter.zipTags(
                                        Collections.singletonList("foo"),
                                        Arrays.asList("bar", "baz")))
                .isInstanceOf(InvalidTagFilterException.class)
                .hasMessageContaining("tag_value")
                .hasMessageContaining("tag_key");
        assertThatThrownBy(() -> TaskFilter.zipTags(null, Collections.singletonList("bar")))
                .isInstanceOf(InvalidTagFilterException.class);
    }

    /** Without a state, every state matches; with one, only that state. */
    @Test
    void matchesState() {
        assertThat(TaskFilter.NONE.hasState()).isFalse();
        assertThat(TaskFilter.NONE.matchesState(TesState.RUNNING)).isTrue();
        TaskFilter running = new TaskFilter(null, TesState.RUNNING, null);
        assertThat(running.hasState()).isTrue();
        assertThat(running.matchesState(TesState.RUNNING)).isTrue();
        assertThat(running.matchesState(TesState.QUEUED)).isFalse();
    }

    /** The name filter behaves as name_prefix always did. */
    @Test
    void matchesName() {
        assertThat(TaskFilter.byNamePrefix("align").matchesName("align-1")).isTrue();
        assertThat(TaskFilter.byNamePrefix("align").matchesName("Align-1")).isFalse();
        assertThat(TaskFilter.byNamePrefix("align").matchesName(null)).isFalse();
        assertThat(TaskFilter.byNamePrefix("").matchesName(null)).isTrue();
        assertThat(TaskFilter.NONE.matchesName(null)).isTrue();
    }
}
