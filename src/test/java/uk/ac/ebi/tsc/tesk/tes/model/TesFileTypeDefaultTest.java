package uk.ac.ebi.tsc.tesk.tes.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

/**
 * TES 1.1 doesn't require the type of inputs and outputs: a missing type and an explicit {@code
 * null} are both {@code FILE} (issue #146).
 */
public class TesFileTypeDefaultTest {

    /** A plain mapper; TasksApiControllerTest covers the application's. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TesInput input(String json) throws Exception {
        return this.objectMapper.readValue(json, TesInput.class);
    }

    private TesOutput output(String json) throws Exception {
        return this.objectMapper.readValue(json, TesOutput.class);
    }

    @Test
    public void input_missingOrNullTypeIsFile() throws Exception {
        assertEquals(TesFileType.FILE, input("{\"path\": \"/in\"}").getType());
        assertEquals(TesFileType.FILE, input("{\"path\": \"/in\", \"type\": null}").getType());
        assertEquals(
                TesFileType.DIRECTORY,
                input("{\"path\": \"/in\", \"type\": \"DIRECTORY\"}").getType());
    }

    @Test
    public void output_missingOrNullTypeIsFile() throws Exception {
        assertEquals(TesFileType.FILE, output("{\"path\": \"/out\"}").getType());
        assertEquals(TesFileType.FILE, output("{\"path\": \"/out\", \"type\": null}").getType());
        assertEquals(
                TesFileType.DIRECTORY,
                output("{\"path\": \"/out\", \"type\": \"DIRECTORY\"}").getType());
    }

    /** Unknown or empty types stay null, so that validation refuses them with a 400. */
    @Test
    public void unknownTypeIsNotFile() throws Exception {
        assertNull(input("{\"path\": \"/in\", \"type\": \"UNKNOWN\"}").getType());
        assertNull(input("{\"path\": \"/in\", \"type\": \"\"}").getType());
        assertNull(output("{\"path\": \"/out\", \"type\": \"UNKNOWN\"}").getType());
        assertNull(output("{\"path\": \"/out\", \"type\": \"\"}").getType());
    }
}
