package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_JSON_INPUT_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ANN_TESTASK_NAME_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_JOBTYPE_VALUE_TASKM;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_KEY;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TASKSTATE_VALUE_CANC;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.convert.TesKubernetesConverter;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesListTasksResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * Listing tasks filters them by the prefix of their names, their states and their tags, after
 * Kubernetes has returned a page of taskmaster jobs.
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
                this.service.listTasks(
                        TaskFilter.byNamePrefix("align"), null, null, TaskView.MINIMAL, USER);
        assertThat(ids(response)).containsExactly("task-1", "task-4");
    }

    /** The prefix is case-sensitive, and the whole name may be the prefix. */
    @Test
    void prefixIsCaseSensitiveAndMayBeTheWholeName() {
        this.defaultPage();
        assertThat(
                        ids(
                                this.service.listTasks(
                                        TaskFilter.byNamePrefix("Align"),
                                        null,
                                        null,
                                        TaskView.MINIMAL,
                                        USER)))
                .isEmpty();
        assertThat(
                        ids(
                                this.service.listTasks(
                                        TaskFilter.byNamePrefix("call-1"),
                                        null,
                                        null,
                                        TaskView.MINIMAL,
                                        USER)))
                .containsExactly("task-2");
    }

    /** Without a prefix, or with an empty one, all tasks are listed, also those without names. */
    @Test
    void noOrEmptyPrefixListsAllTasks() {
        this.defaultPage();
        assertThat(
                        ids(
                                this.service.listTasks(
                                        TaskFilter.byNamePrefix(null),
                                        null,
                                        null,
                                        TaskView.MINIMAL,
                                        USER)))
                .containsExactly("task-1", "task-2", "task-3", "task-4");
        assertThat(
                        ids(
                                this.service.listTasks(
                                        TaskFilter.byNamePrefix(""),
                                        null,
                                        null,
                                        TaskView.MINIMAL,
                                        USER)))
                .containsExactly("task-1", "task-2", "task-3", "task-4");
    }

    /** Filtering works on the other views, too. */
    @Test
    void filtersBasicView() {
        this.defaultPage();
        assertThat(
                        ids(
                                this.service.listTasks(
                                        TaskFilter.byNamePrefix("call"),
                                        null,
                                        null,
                                        TaskView.BASIC,
                                        USER)))
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
                this.service.listTasks(
                        TaskFilter.byNamePrefix("align"), 2L, null, TaskView.MINIMAL, USER);
        assertThat(ids(first)).isEmpty();
        assertThat(first.getNextPageToken()).isEqualTo("token-2");

        TesListTasksResponse second =
                this.service.listTasks(
                        TaskFilter.byNamePrefix("align"), 2L, "token-2", TaskView.MINIMAL, USER);
        assertThat(ids(second)).containsExactly("task-3");
        assertThat(second.getNextPageToken()).isNull();
    }

    /**
     * If no task of the page matches, the executor and filer jobs and the pods aren't listed: an
     * empty page with the next page's token comes back.
     */
    @Test
    void emptyFilteredPageSkipsOtherLists() {
        this.page(null, 2, "token-2", taskmaster("task-1", "call-1"));
        TesListTasksResponse response =
                this.service.listTasks(
                        TaskFilter.byNamePrefix("align"), 2L, null, TaskView.BASIC, USER);
        assertThat(ids(response)).isEmpty();
        assertThat(response.getNextPageToken()).isEqualTo("token-2");
        verify(this.client, never()).listAllTaskExecutorJobs();
        verify(this.client, never()).listAllFilerJobs();
        verify(this.client, never()).listAllJobPods();
    }

    /** The taskmaster job of a task stored with the job (annotation) as this JSON. */
    private static V1Job stored(String id, String taskJson) {
        V1Job job = taskmaster(id, null);
        job.getMetadata().putAnnotationsItem(ANN_JSON_INPUT_KEY, taskJson);
        return job;
    }

    private static Map<String, String> tags(String... keysAndValues) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            tags.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tags;
    }

    private List<String> listByTags(Map<String, String> tags, TaskView view) {
        return ids(
                this.service.listTasks(new TaskFilter(null, null, tags), null, null, view, USER));
    }

    private List<String> listByState(TesState state, TaskView view) {
        return ids(
                this.service.listTasks(new TaskFilter(null, state, null), null, null, view, USER));
    }

    /**
     * Tags are read from the task stored with the taskmaster's job; tasks without tags, or whose
     * stored task can't be read, match no tag filter (issue #143).
     */
    @Test
    void filtersByTags() {
        this.page(
                null,
                null,
                null,
                stored("task-1", "{\"tags\":{\"WORKFLOW_ID\":\"cwl-01234\",\"LAB\":\"alice\"}}"),
                stored("task-2", "{\"tags\":{\"WORKFLOW_ID\":\"cwl-56789\"}}"),
                stored("task-3", "{\"tags\":{\"WORKFLOW_ID\":\"\"}}"),
                stored("task-4", "{\"executors\":[]}"),
                stored("task-5", "not JSON"),
                stored("task-6", "{\"tags\":[\"WORKFLOW_ID\"]}"),
                taskmaster("task-7", "not stored"));

        assertThat(listByTags(tags("WORKFLOW_ID", "cwl-01234"), TaskView.MINIMAL))
                .containsExactly("task-1");
        assertThat(listByTags(tags("WORKFLOW_ID", ""), TaskView.MINIMAL))
                .containsExactly("task-1", "task-2", "task-3");
        assertThat(listByTags(tags("WORKFLOW_ID", "cwl-01234", "LAB", "alice"), TaskView.BASIC))
                .containsExactly("task-1");
        assertThat(listByTags(tags("WORKFLOW_ID", "cwl-01234", "LAB", "bob"), TaskView.FULL))
                .isEmpty();
        assertThat(listByTags(Collections.emptyMap(), TaskView.MINIMAL))
                .containsExactly(
                        "task-1", "task-2", "task-3", "task-4", "task-5", "task-6", "task-7");
    }

    /** Only tasks in the requested state are listed, in all views (issue #143). */
    @Test
    void filtersByState() {
        V1Job cancelled = taskmaster("task-2", "b");
        cancelled.getMetadata().putLabelsItem(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        this.page(
                null, null, null, taskmaster("task-1", "a"), cancelled, taskmaster("task-3", "c"));

        for (TaskView view : TaskView.values()) {
            assertThat(listByState(TesState.INITIALIZING, view))
                    .containsExactly("task-1", "task-3");
            assertThat(listByState(TesState.CANCELED, view)).containsExactly("task-2");
            assertThat(listByState(TesState.COMPLETE, view)).isEmpty();
        }
    }

    /** All filters must match, and a filtered page keeps the next page's token (issue #143). */
    @Test
    void combinesFiltersAndKeepsNextPageToken() {
        V1Job cancelled = stored("task-2", "{\"tags\":{\"foo\":\"bar\"}}");
        cancelled.getMetadata().putAnnotationsItem(ANN_TESTASK_NAME_KEY, "align-2");
        cancelled.getMetadata().putLabelsItem(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        V1Job running = stored("task-1", "{\"tags\":{\"foo\":\"bar\"}}");
        running.getMetadata().putAnnotationsItem(ANN_TESTASK_NAME_KEY, "align-1");
        V1Job otherName = stored("task-3", "{\"tags\":{\"foo\":\"bar\"}}");
        otherName.getMetadata().putAnnotationsItem(ANN_TESTASK_NAME_KEY, "call-3");
        otherName.getMetadata().putLabelsItem(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        this.page(null, 3, "token-2", running, cancelled, otherName);

        TesListTasksResponse response =
                this.service.listTasks(
                        new TaskFilter("align", TesState.CANCELED, tags("foo", "bar")),
                        3L,
                        null,
                        TaskView.MINIMAL,
                        USER);
        assertThat(ids(response)).containsExactly("task-2");
        assertThat(response.getNextPageToken()).isEqualTo("token-2");
    }

    /**
     * If the tags rule out every task of the page, the other jobs and the pods aren't listed (issue
     * #143).
     */
    @Test
    void tagFilterSparesOtherLists() {
        this.page(null, 2, "token-2", stored("task-1", "{\"tags\":{\"foo\":\"bar\"}}"));

        TesListTasksResponse response =
                this.service.listTasks(
                        new TaskFilter(null, null, tags("foo", "baz")),
                        2L,
                        null,
                        TaskView.BASIC,
                        USER);
        assertThat(ids(response)).isEmpty();
        assertThat(response.getNextPageToken()).isEqualTo("token-2");
        verify(this.client, never()).listAllTaskExecutorJobs();
        verify(this.client, never()).listAllFilerJobs();
        verify(this.client, never()).listAllJobPods();
    }

    /** A state filter that matches no task of the page gives an empty page (issue #143). */
    @Test
    void stateFilterCanGiveEmptyPage() {
        this.page(null, 2, "token-2", taskmaster("task-1", "a"));

        TesListTasksResponse response =
                this.service.listTasks(
                        new TaskFilter(null, TesState.COMPLETE, null),
                        2L,
                        null,
                        TaskView.FULL,
                        USER);
        assertThat(ids(response)).isEmpty();
        assertThat(response.getNextPageToken()).isEqualTo("token-2");
    }
}
