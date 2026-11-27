package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.tes.model.TesExecutor;
import uk.ac.ebi.tsc.tesk.tes.model.TesInput;
import uk.ac.ebi.tsc.tesk.tes.model.TesOutput;
import uk.ac.ebi.tsc.tesk.tes.model.TesResources;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;
import uk.ac.ebi.tsc.tesk.tes.model.TesTaskLog;

/** The projection of a submitted task for CreateTask (the full case is in TesAuthzTest) */
public class TaskProjectionTest {

    @Test
    public void of_emptyTask() {
        assertThat(TaskProjection.of(new TesTask()))
                .isEqualTo(Collections.singletonMap("executors", Collections.emptyList()));
    }

    /**
     * What the server sets is never projected: client-supplied id, state, creation_time and logs
     * (#229)
     */
    @Test
    public void of_ignoresServerSetFields() {
        TesTask task = new TesTask();
        task.setName("t");
        task.setId("task-client");
        task.setState(TesState.COMPLETE);
        task.setCreationTime("2020-01-01T00:00:00Z");
        task.setLogs(Arrays.asList(new TesTaskLog()));
        TesTask plain = new TesTask();
        plain.setName("t");

        assertThat(TaskProjection.of(task)).isEqualTo(TaskProjection.of(plain));
    }

    @Test
    public void of_leavesOutUnsetValues() {
        TesTask task = new TesTask();
        TesResources resources = new TesResources();
        resources.setZones(Arrays.asList(null, "zone-a"));
        task.setResources(resources);
        task.setVolumes(Arrays.asList("/vol", null));
        TesInput input = new TesInput();
        input.setType(null);
        TesOutput output = new TesOutput();
        // left out by the stripping, as in tes-authz's golden cases
        output.setUrl("user@host:path");
        task.setInputs(Arrays.asList(input, null));
        task.setOutputs(Arrays.asList(null, output));
        task.setExecutors(Arrays.asList((TesExecutor) null));

        Map<String, Object> projection = TaskProjection.of(task);

        assertThat(projection.get("resources"))
                .isEqualTo(Collections.singletonMap("zones", Arrays.asList("zone-a")));
        assertThat(projection.get("volumes")).isEqualTo(Arrays.asList("/vol"));
        assertThat(projection.get("inputs")).isEqualTo(Arrays.asList(Collections.emptyMap()));
        assertThat(projection.get("outputs"))
                .isEqualTo(Arrays.asList(Collections.singletonMap("type", "FILE")));
        assertThat(projection.get("executors")).isEqualTo(Collections.emptyList());
    }
}
