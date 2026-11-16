package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.LABEL_TESTASK_ID_KEY;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
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
        when(this.client.listFilerJobsOfTasks(any())).thenReturn(new V1JobList());
        when(this.client.listUnlabelledFilerJobs()).thenReturn(new V1JobList());
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
        verify(this.client, never()).listFilerJobsOfTasks(any());
        verify(this.client, never()).listUnlabelledFilerJobs();
        verify(this.client, never()).listAllJobPods();
    }

    /** The taskmaster job of a task stored with the job (annotation) as this JSON. */
    private static V1Job stored(String id, String taskJson) {
        V1Job job = taskmaster(id, null);
        job.getMetadata().putAnnotationsItem(ANN_JSON_INPUT_KEY, taskJson);
        return job;
    }

    private static List<Map.Entry<String, String>> tags(String... keysAndValues) {
        return TaskFilterTest.filterTags(keysAndValues);
    }

    private List<String> listByTags(List<Map.Entry<String, String>> tags, TaskView view) {
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
                taskmaster("task-7", "not stored"),
                stored("task-8", "{\"tags\":{\"WORKFLOW_ID\":null}}"));

        assertThat(listByTags(tags("WORKFLOW_ID", "cwl-01234"), TaskView.MINIMAL))
                .containsExactly("task-1");
        assertThat(listByTags(tags("WORKFLOW_ID", ""), TaskView.MINIMAL))
                .containsExactly("task-1", "task-2", "task-3", "task-8");
        assertThat(listByTags(tags("WORKFLOW_ID", "cwl-01234", "LAB", "alice"), TaskView.BASIC))
                .containsExactly("task-1");
        assertThat(listByTags(tags("WORKFLOW_ID", "cwl-01234", "LAB", "bob"), TaskView.FULL))
                .isEmpty();
        assertThat(
                        listByTags(
                                tags("WORKFLOW_ID", "cwl-01234", "WORKFLOW_ID", "cwl-56789"),
                                TaskView.MINIMAL))
                .isEmpty();
        assertThat(listByTags(Collections.emptyList(), TaskView.MINIMAL))
                .containsExactly(
                        "task-1", "task-2", "task-3", "task-4", "task-5", "task-6", "task-7",
                        "task-8");
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
            assertThat(listByState(TesState.CANCELING, view)).containsExactly("task-2");
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
                        new TaskFilter("align", TesState.CANCELING, tags("foo", "bar")),
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
        verify(this.client, never()).listFilerJobsOfTasks(any());
        verify(this.client, never()).listUnlabelledFilerJobs();
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

    /**
     * A filer job of the task with the given ID: the inputs or outputs filer's (suffix), labelled
     * with the task's ID as taskmasters with tesk-core#147 do it, or not (as older ones), and
     * failed.
     */
    private static V1Job failedFiler(String taskId, String suffix, boolean labelled) {
        V1ObjectMeta metadata = new V1ObjectMeta().name(taskId + suffix);
        if (labelled) {
            metadata.putLabelsItem(LABEL_TESTASK_ID_KEY, taskId);
        }
        return new V1Job()
                .metadata(metadata)
                .status(
                        new V1JobStatus()
                                .failed(1)
                                .addConditionsItem(
                                        new V1JobCondition().type("Failed").status("True")));
    }

    private static V1JobList jobList(V1Job... jobs) {
        V1JobList list = new V1JobList();
        Arrays.stream(jobs).forEach(list::addItemsItem);
        return list;
    }

    /**
     * Only the filer jobs of the page's matching tasks are listed, by their label (issue #177); the
     * unlabelled filer jobs of older taskmasters aren't, if every matching task has a labelled
     * filer job. A failed outputs filer makes its task fail, so it was combined with its task.
     */
    @Test
    void listsOnlyFilerJobsOfMatchingTasks() {
        this.page(
                null,
                null,
                null,
                taskmaster("task-1", "align-1"),
                taskmaster("task-2", "call-1"),
                taskmaster("task-3", "align-2"));
        when(this.client.listFilerJobsOfTasks(Arrays.asList("task-1", "task-3")))
                .thenReturn(
                        jobList(
                                failedFiler("task-1", "-outputs-filer", true),
                                failedFiler("task-3", "-inputs-filer", true)));

        TesListTasksResponse response =
                this.service.listTasks(
                        TaskFilter.byNamePrefix("align"), null, null, TaskView.MINIMAL, USER);

        assertThat(response.getTasks())
                .extracting(TesTask::getId, TesTask::getState)
                .containsExactly(
                        tuple("task-1", TesState.SYSTEM_ERROR),
                        tuple("task-3", TesState.INITIALIZING));
        verify(this.client).listFilerJobsOfTasks(Arrays.asList("task-1", "task-3"));
        verify(this.client, never()).listUnlabelledFilerJobs();
    }

    /**
     * Tasks started by taskmasters before tesk-core#147 have filer jobs without the label: if a
     * matching task has no labelled filer job, the unlabelled filer jobs are listed too, so that
     * those tasks keep their state (issue #177).
     */
    @Test
    void fallsBackToUnlabelledFilerJobs() {
        this.page(null, null, null, taskmaster("task-1", "new"), taskmaster("task-2", "old"));
        when(this.client.listFilerJobsOfTasks(Arrays.asList("task-1", "task-2")))
                .thenReturn(jobList(failedFiler("task-1", "-outputs-filer", true)));
        when(this.client.listUnlabelledFilerJobs())
                .thenReturn(
                        jobList(
                                failedFiler("task-2", "-outputs-filer", false),
                                failedFiler("task-9", "-outputs-filer", false)));

        TesListTasksResponse response =
                this.service.listTasks(
                        TaskFilter.byNamePrefix(null), null, null, TaskView.MINIMAL, USER);

        assertThat(response.getTasks())
                .extracting(TesTask::getId, TesTask::getState)
                .containsExactly(
                        tuple("task-1", TesState.SYSTEM_ERROR),
                        tuple("task-2", TesState.SYSTEM_ERROR));
        verify(this.client).listUnlabelledFilerJobs();
    }

    /**
     * A task without inputs, outputs and volumes never has filer jobs: it needs no fallback, also
     * without a labelled filer job (issue #177).
     */
    @Test
    void noFallbackForTasksWithoutFilers() {
        this.page(
                null,
                null,
                null,
                stored("task-1", "{\"executors\":[{}],\"outputs\":[]}"),
                stored("task-2", "{\"executors\":[{}]}"));
        this.service.listTasks(TaskFilter.byNamePrefix(null), null, null, TaskView.MINIMAL, USER);
        verify(this.client, never()).listUnlabelledFilerJobs();
    }

    /**
     * Older taskmasters (before tesk-core 0.11.0) ran the outputs filer also for tasks with inputs
     * but no outputs: such a task without a labelled filer job needs the fallback (issue #177).
     */
    @Test
    void fallsBackForTasksWithInputsOnly() {
        this.page(
                null,
                null,
                null,
                stored("task-1", "{\"executors\":[{}],\"outputs\":[]}"),
                stored("task-2", "{\"executors\":[{}],\"inputs\":[{}],\"outputs\":[]}"));
        this.service.listTasks(TaskFilter.byNamePrefix(null), null, null, TaskView.MINIMAL, USER);
        verify(this.client).listUnlabelledFilerJobs();
    }

    /** A task whose stored task can't be read may have filer jobs: it needs the fallback. */
    @Test
    void fallsBackForUnreadableTasks() {
        this.page(null, null, null, stored("task-1", "not JSON"));
        this.service.listTasks(TaskFilter.byNamePrefix(null), null, null, TaskView.MINIMAL, USER);
        verify(this.client).listUnlabelledFilerJobs();
    }

    /** A filer job without labels (which Kubernetes wouldn't select) counts as unlabelled. */
    @Test
    void filerJobWithoutLabelsCountsAsUnlabelled() {
        this.page(null, null, null, taskmaster("task-1", "a"));
        when(this.client.listFilerJobsOfTasks(Collections.singletonList("task-1")))
                .thenReturn(
                        jobList(
                                new V1Job()
                                        .metadata(new V1ObjectMeta().name("task-1-inputs-filer"))));

        this.service.listTasks(TaskFilter.byNamePrefix(null), null, null, TaskView.MINIMAL, USER);

        verify(this.client).listUnlabelledFilerJobs();
    }
}
