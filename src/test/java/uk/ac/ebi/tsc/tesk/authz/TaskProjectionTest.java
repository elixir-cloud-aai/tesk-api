package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.tes.exception.UncheckableUrlException;
import uk.ac.ebi.tsc.tesk.tes.model.TesExecutor;
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
        task.setInputs(Arrays.asList(input, null));
        task.setOutputs(Arrays.asList(null, output));
        task.setExecutors(Arrays.asList((TesExecutor) null));

        Map<String, Object> projection = TaskProjection.of(task);

        assertThat(projection.get("resources")).isEqualTo(Collections.emptyMap());
        assertThat(projection.get("inputs")).isEqualTo(Arrays.asList(Collections.emptyMap()));
        assertThat(projection.get("outputs"))
                .isEqualTo(Arrays.asList(Collections.singletonMap("type", "FILE")));
        assertThat(projection.get("executors")).isEqualTo(Collections.emptyList());
    }

    /**
     * A URL that the stripping leaves out would look like inline content to the decision service:
     * the create is refused (400), naming the field, not the URL
     */
    @Test
    public void of_refusesUrlsThatCantBePassedOn() {
        TesTask task = new TesTask();
        TesInput fine = new TesInput();
        fine.setUrl("https://h/in");
        TesInput ambiguous = new TesInput();
        ambiguous.setUrl("https://evil.example?a@b/file");
        task.setInputs(Arrays.asList(fine, ambiguous));

        assertThatThrownBy(() -> TaskProjection.of(task))
                .isInstanceOf(UncheckableUrlException.class)
                .hasMessageStartingWith("inputs[1].url can't be checked")
                .hasMessageNotContaining("evil");

        TesOutput output = new TesOutput();
        output.setUrl("user@host:path");
        task.setInputs(null);
        task.setOutputs(Arrays.asList(output));
        assertThatThrownBy(() -> TaskProjection.of(task))
                .isInstanceOf(UncheckableUrlException.class)
                .hasMessageStartingWith("outputs[0].url");
    }
}
