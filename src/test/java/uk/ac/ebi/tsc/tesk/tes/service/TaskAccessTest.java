package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.CancelDecision;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.ListScope;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.Operation;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.TaskFacts;

/** The owner rule as decisions, the names of the operations, and the facts of a task */
public class TaskAccessTest {

    private final User alice = User.builder("https://idp|alice").build();
    private final TaskFacts own = new TaskFacts("https://idp|alice", TesState.RUNNING, null);
    private final TaskFacts foreign = new TaskFacts("https://idp|bob", TesState.QUEUED, Map.of());

    @Test
    public void operation_settings() {
        assertThat(Operation.GET.setting()).isEqualTo("get");
        assertThat(Operation.ofSetting("cancel")).contains(Operation.CANCEL);
        assertThat(Operation.ofSetting("CANCEL")).isEmpty();
        assertThat(Operation.ofSetting(null)).isEmpty();
    }

    @Test
    public void ownerOnly_decidesNothing() {
        for (Operation operation : Operation.values()) {
            assertThat(TaskAccess.OWNER_ONLY.decides(operation)).isFalse();
        }
    }

    @Test
    public void ownerOnly_get() {
        assertThat(TaskAccess.OWNER_ONLY.mayGet(this.alice, "a", this.own, TaskView.MINIMAL))
                .isTrue();
        assertThat(TaskAccess.OWNER_ONLY.mayGet(this.alice, "b", this.foreign, TaskView.MINIMAL))
                .isFalse();
        assertThat(TaskAccess.OWNER_ONLY.mayGet(this.alice, "c", null, TaskView.MINIMAL)).isFalse();
    }

    @Test
    public void ownerOnly_cancel() {
        assertThat(TaskAccess.OWNER_ONLY.mayCancel(this.alice, "a", this.own))
                .isEqualTo(CancelDecision.ALLOWED);
        assertThat(TaskAccess.OWNER_ONLY.mayCancel(this.alice, "b", this.foreign))
                .isEqualTo(CancelDecision.NOT_FOUND);
        assertThat(TaskAccess.OWNER_ONLY.mayCancel(this.alice, "c", null))
                .isEqualTo(CancelDecision.NOT_FOUND);
    }

    @Test
    public void ownerOnly_list() {
        assertThat(TaskAccess.OWNER_ONLY.listScope(this.alice, TaskView.FULL))
                .isEqualTo(ListScope.OWN);
    }

    /** Tags are copied, keep null values, and are empty if the task has none */
    @Test
    public void facts_tags() {
        Map<String, String> tags = new HashMap<>();
        tags.put("k", null);
        TaskFacts facts = new TaskFacts("o", TesState.RUNNING, tags);
        tags.put("later", "x");
        assertThat(facts.tags()).containsOnlyKeys("k");
        assertThat(this.own.tags()).isEmpty();
        assertThat(this.own.isOwnedBy(this.alice)).isTrue();
        assertThat(this.foreign.isOwnedBy(this.alice)).isFalse();
        assertThat(this.alice.isOwner(null)).isFalse();
    }
}
