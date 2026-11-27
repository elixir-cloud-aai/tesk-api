package uk.ac.ebi.tsc.tesk.tes.service;

import org.springframework.security.access.prepost.PreAuthorize;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.k8s.convert.data.Task;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesCreateTaskResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesListTasksResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;

/**
 * Implementation of controller endpoints. Orchestrates all kubernetes client calls (via wrapper)
 * and converting inputs and outputs with the help of the converter.
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
public interface TesService {
    /**
     * Creates new TES task, by converting input and calling create method. In case of detecting
     * duplicated task ID, retries with new generated ID (up to a limit of retries). The task is
     * stamped with its creator, its only owner.
     *
     * <p>With authentication, the decision service (AuthZEN, e.g. tes-authz) decides whether the
     * caller may create the task, before anything is created ({@code CreateAuthorisation}): 403 if
     * it denies, 503 if it can't decide now. Without authentication (noauth), every caller may.
     */
    @PreAuthorize("@createAuthorisation.mayCreate(#task, #user)")
    TesCreateTaskResponse createTask(TesTask task, User user);

    /**
     * Gets single task's details based on ID. Performs a series of kubernetes API calls and
     * converts results with means of the converter. Only the task's creator may get it ({@link
     * User#isCreatorOf}); for any other caller, a task of another creator is not found, like a
     * missing one (404). If getting is switched on ({@code tesk.api.authz.operations}), the
     * decision service decides instead ({@link TaskAccess}), also for missing tasks.
     *
     * @param taskId - TES task ID (==taskmaster's job name)
     * @param view - one of {@link TaskView} values, decides on how much detail is put in results
     * @return - TES task details
     */
    TesTask getTask(String taskId, TaskView view, User user);

    /**
     * Gets a full list of tasks. Performs Kubernetes API batch calls (all taskmasters, all
     * executors, all pods), combines them together into valid {@link Task} objects and converts to
     * result with means of the converter. Only the caller's own tasks ({@link
     * User#getLabelSelector}, {@link User#isCreatorOf}); if listing is switched on ({@code
     * tesk.api.authz.operations}), the tasks of the decision service's list scope ({@link
     * TaskAccess}): own, all (with an exact owner) or none.
     *
     * @param filter - only tasks that match it (name prefix, state and tags; see {@link
     *     TaskFilter}); {@link TaskFilter#NONE} for all tasks
     * @param pageSize - maximum number of taskmaster jobs read for the page; all if null (the API
     *     passes its default of 256 instead, see TasksApiController)
     * @param pageToken - continue token of Kubernetes, from the previous page's next_page_token
     * @param view - one of {@link TaskView} values, decides on how much detail is put in each
     *     resulting task
     * @return - the page of tasks, with the continue token of the next page, if there is one
     */
    TesListTasksResponse listTasks(
            TaskFilter filter, Long pageSize, String pageToken, TaskView view, User user);

    /**
     * Cancels a task with a given id, by setting a label to taskmaster Job and Pod object. If not
     * task with a given Id - throws TaskNotFoundException If task completed, throws
     * CancelNotRunningTask. Only the task's creator may cancel it ({@link User#isCreatorOf}); for
     * any other caller, a task of another creator is not found, like a missing one (404). If
     * cancelling is switched on ({@code tesk.api.authz.operations}), the decision service decides
     * instead ({@link TaskAccess}): 403 if it denies, but the caller may get the task.
     *
     * @param taskId - TES task ID (==taskmaster's job name)
     * @param user - the caller
     */
    void cancelTask(String taskId, User user);
}
