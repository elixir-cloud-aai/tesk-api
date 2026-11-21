package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * URL stripping of the profile tes-authzen/1: the shared golden table ({@code
 * authz/url-stripping.json}, the same rows for every TES implementation)
 */
public class UrlStripperTest {

    static Stream<Arguments> golden() throws IOException {
        try (InputStream in =
                UrlStripperTest.class.getResourceAsStream("/authz/url-stripping.json")) {
            JsonNode rows = JsonMapper.builder().build().readTree(in);
            List<Arguments> cases = new ArrayList<>();
            for (JsonNode row : rows) {
                JsonNode expected = row.get("expected");
                cases.add(
                        Arguments.of(
                                row.get("description").asString(),
                                row.get("input").asString(),
                                expected.isNull() ? null : expected.asString()));
            }
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("golden")
    public void strip_matchesGoldenTable(String description, String input, String expected) {
        assertThat(UrlStripper.strip(input)).isEqualTo(expected);
    }

    @Test
    public void golden_hasAllRows() throws IOException {
        assertThat(golden().count()).isGreaterThanOrEqualTo(36);
    }

    @Test
    public void strip_null() {
        assertThat(UrlStripper.strip(null)).isNull();
    }

    /** Values without a scheme keep their fragment (only '@' and '?' omit them) */
    @Test
    public void strip_noSchemeKeepsFragment() {
        assertThat(UrlStripper.strip("/local/path#x")).isEqualTo("/local/path#x");
    }

    /** Step 0: a tab, line feed or carriage return anywhere omits the value */
    @ParameterizedTest
    @ValueSource(strings = {"https://h/a\tb", "https://h/a\nb", "https://h/a\rb", "/a\tb"})
    public void strip_controlWhitespace(String url) {
        assertThat(UrlStripper.strip(url)).isNull();
    }

    @Test
    public void strip_fragmentBeforeQueryInAuthority() {
        assertThat(UrlStripper.strip("https://u@h#f?q")).isEqualTo("https://h");
    }
}
