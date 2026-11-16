package uk.ac.ebi.tsc.tesk.tes.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The {@code view} parameter of GetTask and ListTasks */
public class TaskViewTest {

    /** Views are named in any case */
    @Test
    public void fromString_anyCase() {
        assertThat(TaskView.fromString("full")).isEqualTo(TaskView.FULL);
        assertThat(TaskView.fromString("BASIC")).isEqualTo(TaskView.BASIC);
    }

    /** Unknown views are MINIMAL, the default of TES */
    @Test
    public void fromString_unknownIsMinimal() {
        assertThat(TaskView.fromString("everything")).isEqualTo(TaskView.MINIMAL);
    }
}
