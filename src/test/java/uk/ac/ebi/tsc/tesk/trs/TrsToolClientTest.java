package uk.ac.ebi.tsc.tesk.trs;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.restclient.RestTemplateBuilder;

/**
 * @author aniewielska
 * @since 30/03/2021
 */
public class TrsToolClientTest {

    private static final String URI_PATTERN_1 = "trs://{host}/{id}/versions/{version}";
    private static final String URI_PATTERN_2 = "protocol{host}_{id}_{version}";

    static Stream<Arguments> data() {
        return Stream.of(
                Arguments.of(
                        URI_PATTERN_1,
                        "trs://example.com/identifier/versions/123",
                        true,
                        "example.com",
                        "identifier",
                        "123"),
                Arguments.of(URI_PATTERN_1, "trs://example.com/identifier/123", false, "", "", ""),
                Arguments.of(
                        URI_PATTERN_2,
                        "protocolexample.com_identifier_123",
                        true,
                        "example.com",
                        "identifier",
                        "123"),
                Arguments.of(
                        URI_PATTERN_2, "protocoexample.com_identifier_123", false, "", "", ""));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testTranslateTrsURI_match(
            String uriPattern,
            String uri,
            boolean expectedMatch,
            String host,
            String id,
            String version) {
        String[] results = new String[3];
        TrsProperties properties = new TrsProperties();
        properties.setUriPattern(uriPattern);
        TrsToolClient subject = new TrsToolClient(new RestTemplateBuilder(), properties);
        boolean match = subject.translateTrsURI(uri, results);
        assertThat(match, is(expectedMatch));
        if (expectedMatch) {
            assertThat(results[0], is(host));
            assertThat(results[1], is(id));
            assertThat(results[2], is(version));
        }
    }
}
