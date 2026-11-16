package uk.ac.ebi.tsc.tesk.tes.api;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.StringArrayPropertyEditor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import uk.ac.ebi.tsc.tesk.authz.TesAuthz;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageSizeException;
import uk.ac.ebi.tsc.tesk.tes.model.*;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.service.TaskFilter;
import uk.ac.ebi.tsc.tesk.tes.service.TesService;

@jakarta.annotation.Generated(
        value = "io.swagger.codegen.languages.SpringCodegen",
        date = "2017-11-07T14:45:12.993Z")
@Controller
@RequiredArgsConstructor
@RequestMapping("${openapi.taskExecutionService.base-path:/ga4gh/tes/v1}")
public class TasksApiController implements TasksApi {

    /**
     * Largest page_size accepted by listTasks. The TES 1.1 API description says "less than 2048";
     * TESK API also accepts 2048 itself (issue #114).
     */
    static final long MAX_PAGE_SIZE = 2048;

    /** page_size of listTasks if it is left out or empty: 256, the default of TES (issue #137) */
    static final long DEFAULT_PAGE_SIZE = 256;

    private final TesService tesService;

    /** The required claim (an AND gate before all other rules) and the decision service, if set */
    private final TesAuthz authz;

    /**
     * Keeps each value of a repeated parameter as it is: Spring would split a single value at
     * commas (e.g. {@code tag_key=a,b} into two keys), but tag keys and values may contain them.
     * Only {@code tag_key} and {@code tag_value} are arrays.
     */
    @InitBinder
    public void keepCommasInArrayParameters(WebDataBinder binder) {
        binder.registerCustomEditor(
                String[].class, new StringArrayPropertyEditor(null, null, false, false));
    }

    public ResponseEntity<Object> cancelTask(
            @Parameter(required = true) @PathVariable("id") String id) {
        User user = this.getUser();
        this.authz.gate(user);
        if (this.authz.isDecisionService()) {
            this.tesService.cancelTask(id, user);
        } else {
            // getTask - for authZ purposes (cancellation only possible for the same tasks, a user
            // can actually see
            this.tesService.getTask(id, TaskView.MINIMAL, user);
            this.tesService.cancelTask(id);
        }
        // the specification's CancelTaskResponse is an empty object
        return new ResponseEntity<>(Collections.emptyMap(), HttpStatus.OK);
    }

    public ResponseEntity<TesCreateTaskResponse> createTask(
            @Parameter(required = true) @Valid @RequestBody TesTask body) {
        User user = this.getUser();
        this.authz.gate(user);
        TesCreateTaskResponse response = this.tesService.createTask(body, user);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    public ResponseEntity<TesTask> getTask(
            @Parameter(required = true) @PathVariable("id") String id,
            @Parameter(
                            description =
                                    "OPTIONAL. Affects the fields included in the returned Task messages. See TaskView below.   - MINIMAL: Task message will include ONLY the fields:   Task.Id   Task.State  - BASIC: Task message will include all fields EXCEPT:   Task.ExecutorLog.stdout   Task.ExecutorLog.stderr   Input.content   TaskLog.system_logs  - FULL: Task message includes all fields.",
                            schema =
                                    @Schema(
                                            allowableValues = {"MINIMAL", "BASIC", "FULL"},
                                            defaultValue = "MINIMAL"))
                    @RequestParam(value = "view", required = false, defaultValue = "MINIMAL")
                    String view) {

        User user = this.getUser();
        this.authz.gate(user);
        TaskView taskView = TaskView.fromString(view);
        TesTask task = this.tesService.getTask(id, taskView, user);
        if (taskView == TaskView.MINIMAL) {
            task.setLogs(null);
        }
        return new ResponseEntity<>(task, HttpStatus.OK);
    }

    public ResponseEntity<TesListTasksResponse> listTasks(
            @Parameter(
                            description =
                                    "OPTIONAL. Filter the list to include tasks where the name matches this prefix. If unspecified, no task name filtering is done.")
                    @RequestParam(value = "name_prefix", required = false)
                    String namePrefix,
            @Parameter(
                            description =
                                    "OPTIONAL. Filter tasks by state. Other values than those of tesState give 400 Bad Request. Pages can be shorter, also with state.")
                    @RequestParam(value = "state", required = false)
                    TesState state,
            @Parameter(
                            description =
                                    "OPTIONAL. Filter tasks by tags: a task must have each tag_key, with the tag_value of the same position; a key without a value, or with an empty one, matches any value. Repeat the parameter for more tags. Pages can be shorter, also with tags.")
                    @RequestParam(value = "tag_key", required = false)
                    String[] tagKey,
            @Parameter(
                            description =
                                    "OPTIONAL. The values of the tags in tag_key, in the same order. More values than keys give 400 Bad Request.")
                    @RequestParam(value = "tag_value", required = false)
                    String[] tagValue,
            @Parameter(
                            description =
                                    "OPTIONAL. Number of tasks to return in one page, from 1 to "
                                            + MAX_PAGE_SIZE
                                            + ". Defaults to "
                                            + DEFAULT_PAGE_SIZE
                                            + ". Pages can be shorter, also with name_prefix.",
                            example = "256")
                    @RequestParam(value = "page_size", required = false)
                    Long pageSize,
            @Parameter(
                            description =
                                    "OPTIONAL. Page token is used to retrieve the next page of results. If unspecified, returns the first page of results. See ListTasksResponse.next_page_token")
                    @RequestParam(value = "page_token", required = false)
                    String pageToken,
            @Parameter(
                            description =
                                    "OPTIONAL. Affects the fields included in the returned Task messages. See TaskView below.   - MINIMAL: Task message will include ONLY the fields:   Task.Id   Task.State  - BASIC: Task message will include all fields EXCEPT:   Task.ExecutorLog.stdout   Task.ExecutorLog.stderr   Input.content   TaskLog.system_logs  - FULL: Task message includes all fields.",
                            schema =
                                    @Schema(
                                            allowableValues = {"MINIMAL", "BASIC", "FULL"},
                                            defaultValue = "MINIMAL"))
                    @RequestParam(value = "view", required = false, defaultValue = "MINIMAL")
                    String view) {

        User user = this.getUser();
        this.authz.gate(user);
        long size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPageSizeException(MAX_PAGE_SIZE);
        }
        TaskFilter filter =
                new TaskFilter(
                        namePrefix, state, TaskFilter.zipTags(asList(tagKey), asList(tagValue)));
        TesListTasksResponse response =
                this.tesService.listTasks(filter, size, pageToken, TaskView.fromString(view), user);
        return new ResponseEntity<TesListTasksResponse>(response, HttpStatus.OK);
    }

    private static List<String> asList(String[] values) {
        return values == null ? null : Arrays.asList(values);
    }

    /**
     * The {@link User} principal of the request, or, without one (noauth), a user without groups
     * named after the authentication
     */
    private User getUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication.getPrincipal() instanceof User) {
            return (User) authentication.getPrincipal();
        }
        return User.builder(authentication.getName()).build();
    }
}
