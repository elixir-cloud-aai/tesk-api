package uk.ac.ebi.tsc.tesk.tes.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.junit.Test;

/** The task fields that TES 1.1 added (TESK#276) */
public class Tes11FieldsTest {

    @Test
    public void output_pathPrefix() {
        TesOutput output = new TesOutput().path("/data/*.bam").url("s3://b/out");
        assertNull(output.getPathPrefix());
        assertSame(output, output.pathPrefix("/data"));
        assertEquals("/data", output.getPathPrefix());
        output.setPathPrefix("/other");
        assertEquals("/other", output.getPathPrefix());
        assertTrue(output.toString().contains("pathPrefix: /other"));
    }

    @Test
    public void output_equalsAndHashCode_includePathPrefix() {
        TesOutput a = new TesOutput().path("/data/*.bam").url("s3://b/out").pathPrefix("/data");
        TesOutput b = new TesOutput().path("/data/*.bam").url("s3://b/out").pathPrefix("/data");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, b.pathPrefix("/"));
    }

    @Test
    public void input_streamable() {
        TesInput input = new TesInput().path("/data/in.txt").url("s3://b/in.txt");
        assertNull(input.getStreamable());
        assertSame(input, input.streamable(true));
        assertTrue(input.getStreamable());
        input.setStreamable(false);
        assertFalse(input.getStreamable());
        assertTrue(input.toString().contains("streamable: false"));
    }

    @Test
    public void input_equalsAndHashCode_includeStreamable() {
        TesInput a = new TesInput().path("/in").url("s3://b/in").streamable(true);
        TesInput b = new TesInput().path("/in").url("s3://b/in").streamable(true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(
                a, new TesInput().path("/in").url("s3://b/in").content("x").streamable(true));
        assertNotEquals(a, b.streamable(false));
    }

    @Test
    public void executor_ignoreError() {
        TesExecutor executor = new TesExecutor().image("ubuntu");
        assertNull(executor.getIgnoreError());
        assertSame(executor, executor.ignoreError(false));
        assertFalse(executor.getIgnoreError());
        executor.setIgnoreError(true);
        assertTrue(executor.getIgnoreError());
        assertTrue(executor.toString().contains("ignoreError: true"));
    }

    @Test
    public void executor_equalsAndHashCode_includeIgnoreError() {
        TesExecutor a = new TesExecutor().image("ubuntu").ignoreError(false);
        TesExecutor b = new TesExecutor().image("ubuntu").ignoreError(false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(
                a,
                new TesExecutor()
                        .image("ubuntu")
                        .env(Collections.singletonMap("A", "1"))
                        .ignoreError(false));
        assertNotEquals(a, b.ignoreError(true));
    }

    private static TesResources resources(String json) throws Exception {
        return new ObjectMapper().readValue(json, TesResources.class);
    }

    @Test
    public void resources_backendParametersOptional() throws Exception {
        assertTrue(resources("{}").isBackendParametersOptional());
        assertTrue(
                resources("{\"backend_parameters\": {\"VmSize\": \"D64\"}}")
                        .isBackendParametersOptional());
        assertFalse(
                resources(
                                "{\"backend_parameters\": {\"VmSize\": \"D64\"},"
                                        + " \"backend_parameters_strict\": true}")
                        .isBackendParametersOptional());
        assertTrue(
                resources("{\"backend_parameters\": {}, \"backend_parameters_strict\": true}")
                        .isBackendParametersOptional());
        assertTrue(
                resources("{\"backend_parameters_strict\": true}").isBackendParametersOptional());
    }

    private static TesOutput pattern(String path, String prefix) {
        return new TesOutput().url("s3://b/out").path(path).pathPrefix(prefix);
    }

    @Test
    public void output_patternRootAndNormalize() {
        assertEquals("/data", TesOutput.patternRoot("/data/*/out.txt"));
        assertEquals("/data/a", TesOutput.patternRoot("//data/./a/b/../*.bam"));
        assertEquals("", TesOutput.patternRoot("/*.txt"));
        assertEquals("/data/a", TesOutput.patternRoot("/data/a"));
        assertEquals("/", TesOutput.normalize("/../"));
        assertEquals("/a/c", TesOutput.normalize("/a/b/../c/"));
    }

    @Test
    public void output_pathPrefixMustBeAboveThePattern() {
        assertTrue(pattern("/data/*/out.txt", "/data").isPathPrefixAbovePattern());
        assertTrue(pattern("/data/*/out.txt", "/data/").isPathPrefixAbovePattern());
        assertTrue(pattern("/data/a/*.txt", "/data").isPathPrefixAbovePattern());
        assertTrue(pattern("/data/*.txt", "/").isPathPrefixAbovePattern());
        assertFalse(pattern("/data/*/out.txt", "/data/s1").isPathPrefixAbovePattern());
        assertFalse(pattern("/data2/*.txt", "/data").isPathPrefixAbovePattern());
        // not patterns: path_prefix is ignored
        assertTrue(pattern("/data/out.txt", "/elsewhere").isPathPrefixAbovePattern());
        assertTrue(new TesOutput().path("/data/*.txt").isPathPrefixAbovePattern());
        assertTrue(pattern("/data/*.txt", "").isPathPrefixAbovePattern());
        assertTrue(new TesOutput().isPathPrefixAbovePattern());
    }

    @Test
    public void output_patternMustNotHaveWildcardsInItsFirstComponent() {
        assertFalse(pattern("/*.txt", "/").isPatternBelowRoot());
        assertTrue(pattern("/*.txt", "/").isPathPrefixAbovePattern());
        assertTrue(pattern("/data/*.txt", "/").isPatternBelowRoot());
        // without path_prefix, the path is a file name (TES 1.0), as before
        assertTrue(new TesOutput().path("/*.txt").isPatternBelowRoot());
    }

    private static TesOutput output(String url, String path) {
        return new TesOutput().url(url).path(path);
    }

    private static final String[] WILDCARD_PATHS = {
        "/data/out*.txt", "/data/out?.txt", "/data/out[12].txt", "/data/a\nb*.txt"
    };

    private static final String[] OTHER_URLS = {
        "s3://b/out", "http://h/out", "https://h/out", "ftp://h/out", "gs://b/out", "s3+x://b/out"
    };

    /** TES 1.1 requires path_prefix for a path with wildcards (issue #148). */
    @Test
    public void output_wildcardsNeedPathPrefix() {
        for (String path : WILDCARD_PATHS) {
            for (String url : OTHER_URLS) {
                String label = url + " " + path;
                assertFalse(label, output(url, path).isPathPrefixGivenForPattern());
                assertFalse(label, output(url, path).pathPrefix("").isPathPrefixGivenForPattern());
                assertTrue(
                        label, output(url, path).pathPrefix("/data").isPathPrefixGivenForPattern());
            }
        }
    }

    /**
     * file: URLs, and URLs without a scheme, which the filer takes as file:, keep TES 1.0's
     * expansion without path_prefix (issue #148).
     */
    @Test
    public void output_fileUrlsNeedNoPathPrefix() {
        for (String path : WILDCARD_PATHS) {
            for (String url : new String[] {"file:///out", "FILE:///out", "/out", "out", ":x"}) {
                assertTrue(url + " " + path, output(url, path).isPathPrefixGivenForPattern());
            }
        }
        assertTrue(TesOutput.isFileUrl("file:out"));
        assertFalse(TesOutput.isFileUrl("files://h/out"));
        // urlsplit skips leading blanks, too
        assertFalse(TesOutput.isFileUrl(" s3://b/out"));
        assertFalse(TesOutput.isFileUrl("\ts3://b/out"));
        assertTrue(TesOutput.isFileUrl(" file:///out"));
        // and deletes tabs and line breaks anywhere
        assertFalse(TesOutput.isFileUrl("s\t3://b/out"));
        assertFalse(TesOutput.isFileUrl("ht\ntp://h/out"));
        assertFalse(TesOutput.isFileUrl("http\r://h/out"));
    }

    /** Paths without wildcards, and outputs without path or url, aren't checked (issue #148). */
    @Test
    public void output_withoutWildcardsNeedsNoPathPrefix() {
        assertTrue(output("s3://b/out", "/data/out.txt").isPathPrefixGivenForPattern());
        assertTrue(new TesOutput().path("/data/*.txt").isPathPrefixGivenForPattern());
        assertTrue(new TesOutput().url("s3://b/out").isPathPrefixGivenForPattern());
        assertTrue(new TesOutput().isPathPrefixGivenForPattern());
    }
}
