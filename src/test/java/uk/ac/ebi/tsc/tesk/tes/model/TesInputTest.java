package uk.ac.ebi.tsc.tesk.tes.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TesInputTest {

    @Test
    public void urlOrContentGiven() {
        assertTrue(new TesInput().url("s3://bucket/file").isUrlOrContentGiven());
        assertTrue(new TesInput().content("data").isUrlOrContentGiven());
        assertTrue(new TesInput().url("s3://bucket/file").content("data").isUrlOrContentGiven());
        assertTrue(new TesInput().url(" ").content(" ").isUrlOrContentGiven());
        // JavaScript's trim keeps the information separators U+001C to U+001F.
        assertTrue(new TesInput().url("\u001c").isUrlOrContentGiven());
    }

    @Test
    public void neitherUrlNorContentGiven() {
        assertFalse(new TesInput().isUrlOrContentGiven());
        assertFalse(new TesInput().url("  ").isUrlOrContentGiven());
        assertFalse(new TesInput().url("\u3000\t").isUrlOrContentGiven());
        assertFalse(new TesInput().url("\t\n\u000b\f\r\u00a0\ufeff\u2007\u202f\u2028\u2029").isUrlOrContentGiven());
        assertFalse(new TesInput().content("").isUrlOrContentGiven());
        assertFalse(new TesInput().url("").content("").isUrlOrContentGiven());
    }
}
