package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidTagFilterException;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;

/** The filters of {@code GET /tasks} (issue #143). */
class TaskFilterTest {

    private static Map<String, String> tags(String... keysAndValues) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            tags.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tags;
    }

    private static boolean matches(Map<String, String> filterTags, Map<String, String> taskTags) {
        return new TaskFilter(null, null, filterTags).matchesTags(taskTags);
    }

    /** The table of tag filters and task tags of the TES 1.1 specification ({@code tag_key}). */
    @Test
    void tagsMatchAsInTheSpecification() {
        assertThat(matches(tags("foo", "bar"), tags("foo", "bar"))).isTrue();
        assertThat(matches(tags("foo", "bar"), tags("foo", "bat"))).isFalse();
        assertThat(matches(tags("foo", ""), tags("foo", ""))).isTrue();
        assertThat(matches(tags("foo", "bar", "baz", "bat"), tags("foo", "bar", "baz", "bat")))
                .isTrue();
        assertThat(matches(tags("foo", "bar"), tags("foo", "bar", "baz", "bat"))).isTrue();
        assertThat(matches(tags("foo", "bar", "baz", "bat"), tags("foo", "bar"))).isFalse();
        assertThat(matches(tags("foo", ""), tags("foo", "bar"))).isTrue();
        assertThat(matches(tags("foo", ""), tags())).isFalse();
    }

    /** A task without tags matches only a filter without tags. */
    @Test
    void taskWithoutTags() {
        assertThat(matches(tags(), null)).isTrue();
        assertThat(matches(tags("foo", ""), null)).isFalse();
        assertThat(TaskFilter.NONE.matchesTags(null)).isTrue();
    }

    /** Values are compared exactly: case-sensitive, a null tag value is no match for "bar". */
    @Test
    void tagValuesAreComparedExactly() {
        assertThat(matches(tags("foo", "bar"), tags("foo", "Bar"))).isFalse();
        assertThat(matches(tags("foo", "bar"), tags("foo", null))).isFalse();
        assertThat(matches(tags("foo", ""), tags("foo", null))).isTrue();
    }

    /** Values belong to the keys in order; keys without one match any value. */
    @Test
    void zipTagsPairsKeysWithValues() {
        assertThat(
                        TaskFilter.zipTags(
                                Arrays.asList("WORKFLOW_ID", "PROJECT"),
                                Collections.singletonList("cwl-01234")))
                .containsExactly(entry("WORKFLOW_ID", "cwl-01234"), entry("PROJECT", ""));
        assertThat(TaskFilter.zipTags(Collections.singletonList("foo"), null))
                .containsExactly(entry("foo", ""));
        assertThat(TaskFilter.zipTags(null, null)).isEmpty();
    }

    /** A repeated key keeps its last value, as in a map. */
    @Test
    void zipTagsLastValueOfARepeatedKeyCounts() {
        assertThat(TaskFilter.zipTags(Arrays.asList("foo", "foo"), Arrays.asList("bar", "baz")))
                .containsExactly(entry("foo", "baz"));
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
        assertThat(TaskFilter.NONE.matchesState(TesState.RUNNING)).isTrue();
        TaskFilter running = new TaskFilter(null, TesState.RUNNING, null);
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
