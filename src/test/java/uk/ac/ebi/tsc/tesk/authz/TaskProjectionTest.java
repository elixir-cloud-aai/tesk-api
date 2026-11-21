package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.tes.model.TesInput;
import uk.ac.ebi.tsc.tesk.tes.model.TesOutput;
import uk.ac.ebi.tsc.tesk.tes.model.TesResources;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/** The projection of a submitted task for CreateTask (the full case is in TesAuthzTest) */
public class TaskProjectionTest {

    @Test
    public void of_emptyTask() {
        assertThat(TaskProjection.of(new TesTask()))
                .isEqualTo(Collections.singletonMap("executors", Collections.emptyList()));
    }

    @Test
    public void of_leavesOutUnsetValues() {
        TesTask task = new TesTask();
        task.setResources(new TesResources());
        TesInput input = new TesInput();
        input.setType(null);
        TesOutput output = new TesOutput();
        output.setUrl("user@host:path");
        task.setInputs(Arrays.asList(input));
        task.setOutputs(Arrays.asList(output));

        Map<String, Object> projection = TaskProjection.of(task);

        assertThat(projection.get("resources")).isEqualTo(Collections.emptyMap());
        assertThat(projection.get("inputs")).isEqualTo(Arrays.asList(Collections.emptyMap()));
        assertThat(projection.get("outputs"))
                .isEqualTo(Arrays.asList(Collections.singletonMap("type", "FILE")));
    }
}
