package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_TESTASK_NAME_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_VALUE_TASKM;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesListTasksResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * Listing tasks filters them by the prefix of their names, after Kubernetes has returned a page of
 * taskmaster jobs.
 */
class TesServiceImplListTest {

    private static final User USER = User.builder("admin").build();

    private KubernetesClientWrapper client;
    private TesServiceImpl service;

    @BeforeEach
    void setUp() {
        this.client = mock(KubernetesClientWrapper.class);
        TesKubernetesConverter converter =
                new TesKubernetesConverter(null, null, new ObjectMapper(), null, null);
        this.service = new TesServiceImpl(this.client, converter);
        when(this.client.listAllTaskExecutorJobs()).thenReturn(new V1JobList());
        when(this.client.listAllFilerJobs()).thenReturn(new V1JobList());
        when(this.client.listAllJobPods()).thenReturn(new V1PodList());
    }

    /** A taskmaster job of a task with the given ID and name (no name if null). */
    private static V1Job taskmaster(String id, String name) {
        V1ObjectMeta metadata =
                new V1ObjectMeta()
                        .name(id)
                        .putLabelsItem(LABEL_JOBTYPE_KEY, LABEL_JOBTYPE_VALUE_TASKM);
        if (name != null) {
            metadata.putAnnotationsItem(ANN_TESTASK_NAME_KEY, name);
        }
        return new V1Job().metadata(metadata).status(new V1JobStatus().active(1));
    }

    /** Kubernetes returns this page of taskmaster jobs, with this continue token. */
    private void page(String pageToken, Integer pageSize, String continueToken, V1Job... jobs) {
        V1JobList list = new V1JobList().metadata(new V1ListMeta()._continue(continueToken));
        for (V1Job job : jobs) {
            list.addItemsItem(job);
        }
        when(this.client.listAllTaskmasterJobsForUser(eq(pageToken), eq(pageSize), any(User.class)))
                .thenReturn(list);
    }

    private static List<String> ids(TesListTasksResponse response) {
        return response.getTasks().stream().map(TesTask::getId).collect(Collectors.toList());
    }

    private void defaultPage() {
        this.page(
                null,
                null,
                null,
                taskmaster("task-1", "align-1"),
                taskmaster("task-2", "call-1"),
                taskmaster("task-3", null),
                taskmaster("task-4", "align-2"));
    }

    /** Only tasks whose names start with the prefix are listed, in Kubernetes' order. */
    @Test
    void filtersByNamePrefix() {
        this.defaultPage();
        TesListTasksResponse response =
                this.service.listTasks("align", null, null, TaskView.MINIMAL, USER);
        assertThat(ids(response)).containsExactly("task-1", "task-4");
    }

    /** The prefix is case-sensitive, and the whole name may be the prefix. */
    @Test
    void prefixIsCaseSensitiveAndMayBeTheWholeName() {
        this.defaultPage();
        assertThat(ids(this.service.listTasks("Align", null, null, TaskView.MINIMAL, USER)))
                .isEmpty();
        assertThat(ids(this.service.listTasks("call-1", null, null, TaskView.MINIMAL, USER)))
                .containsExactly("task-2");
    }

    /** Without a prefix, or with an empty one, all tasks are listed, also those without names. */
    @Test
    void noOrEmptyPrefixListsAllTasks() {
        this.defaultPage();
        assertThat(ids(this.service.listTasks(null, null, null, TaskView.MINIMAL, USER)))
                .containsExactly("task-1", "task-2", "task-3", "task-4");
        assertThat(ids(this.service.listTasks("", null, null, TaskView.MINIMAL, USER)))
                .containsExactly("task-1", "task-2", "task-3", "task-4");
    }

    /** Filtering works on the other views, too. */
    @Test
    void filtersBasicView() {
        this.defaultPage();
        assertThat(ids(this.service.listTasks("call", null, null, TaskView.BASIC, USER)))
                .containsExactly("task-2");
    }

    /**
     * A page may be shorter than page_size after filtering, even empty; next_page_token still
     * continues with Kubernetes' next page.
     */
    @Test
    void filteredPageKeepsNextPageToken() {
        this.page(
                null, 2, "token-2", taskmaster("task-1", "call-1"), taskmaster("task-2", "call-2"));
        this.page("token-2", 2, null, taskmaster("task-3", "align-1"), taskmaster("task-4", "x"));

        TesListTasksResponse first =
                this.service.listTasks("align", 2L, null, TaskView.MINIMAL, USER);
        assertThat(ids(first)).isEmpty();
        assertThat(first.getNextPageToken()).isEqualTo("token-2");

        TesListTasksResponse second =
                this.service.listTasks("align", 2L, "token-2", TaskView.MINIMAL, USER);
        assertThat(ids(second)).containsExactly("task-3");
        assertThat(second.getNextPageToken()).isNull();
    }
}
