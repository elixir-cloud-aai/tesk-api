package uk.ac.ebi.tsc.tesk.tes.api;

import io.swagger.annotations.ApiParam;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.StringArrayPropertyEditor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageSizeException;
import uk.ac.ebi.tsc.tesk.tes.model.*;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.service.TaskFilter;
import uk.ac.ebi.tsc.tesk.tes.service.TesService;

@javax.annotation.Generated(
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

    private final TesService tesService;

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
            @ApiParam(value = "", required = true) @PathVariable("id") String id) {
        // getTask - for authZ purposes (cancellation only possible for the same tasks, a user can
        // actually see
        this.tesService.getTask(id, TaskView.MINIMAL, getUser());
        this.tesService.cancelTask(id);
        // the specification's CancelTaskResponse is an empty object
        return new ResponseEntity<>(Collections.emptyMap(), HttpStatus.OK);
    }

    public ResponseEntity<TesCreateTaskResponse> createTask(
            @ApiParam(value = "", required = true) @Valid @RequestBody TesTask body) {
        TesCreateTaskResponse response = this.tesService.createTask(body, this.getUser());
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    public ResponseEntity<TesTask> getTask(
            @ApiParam(value = "", required = true) @PathVariable("id") String id,
            @ApiParam(
                            value =
                                    "OPTIONAL. Affects the fields included in the returned Task messages. See TaskView below.   - MINIMAL: Task message will include ONLY the fields:   Task.Id   Task.State  - BASIC: Task message will include all fields EXCEPT:   Task.ExecutorLog.stdout   Task.ExecutorLog.stderr   Input.content   TaskLog.system_logs  - FULL: Task message includes all fields.",
                            allowableValues = "MINIMAL, BASIC, FULL",
                            defaultValue = "MINIMAL")
                    @RequestParam(value = "view", required = false, defaultValue = "MINIMAL")
                    String view) {

        TaskView taskView = TaskView.fromString(view);
        TesTask task = this.tesService.getTask(id, taskView, this.getUser());
        if (taskView == TaskView.MINIMAL) {
            task.setLogs(null);
        }
        return new ResponseEntity<>(task, HttpStatus.OK);
    }

    public ResponseEntity<TesListTasksResponse> listTasks(
            @ApiParam(
                            value =
                                    "OPTIONAL. Filter the list to include tasks where the name matches this prefix. If unspecified, no task name filtering is done.")
                    @RequestParam(value = "name_prefix", required = false)
                    String namePrefix,
            @ApiParam(
                            value =
                                    "OPTIONAL. Filter tasks by state. Other values than those of tesState give 400 Bad Request. Pages can be shorter, also with state.")
                    @RequestParam(value = "state", required = false)
                    TesState state,
            @ApiParam(
                            value =
                                    "OPTIONAL. Filter tasks by tags: a task must have each tag_key, with the tag_value of the same position; a key without a value, or with an empty one, matches any value. Repeat the parameter for more tags. Pages can be shorter, also with tags.")
                    @RequestParam(value = "tag_key", required = false)
                    String[] tagKey,
            @ApiParam(
                            value =
                                    "OPTIONAL. The values of the tags in tag_key, in the same order. More values than keys give 400 Bad Request.")
                    @RequestParam(value = "tag_value", required = false)
                    String[] tagValue,
            @ApiParam(
                            example = "256",
                            value =
                                    "OPTIONAL. Number of tasks to return in one page, from 1 to "
                                            + MAX_PAGE_SIZE
                                            + ". Without it, all tasks are returned in one page. Pages can be shorter, also with name_prefix.")
                    @RequestParam(value = "page_size", required = false)
                    Long pageSize,
            @ApiParam(
                            value =
                                    "OPTIONAL. Page token is used to retrieve the next page of results. If unspecified, returns the first page of results. See ListTasksResponse.next_page_token")
                    @RequestParam(value = "page_token", required = false)
                    String pageToken,
            @ApiParam(
                            value =
                                    "OPTIONAL. Affects the fields included in the returned Task messages. See TaskView below.   - MINIMAL: Task message will include ONLY the fields:   Task.Id   Task.State  - BASIC: Task message will include all fields EXCEPT:   Task.ExecutorLog.stdout   Task.ExecutorLog.stderr   Input.content   TaskLog.system_logs  - FULL: Task message includes all fields.",
                            allowableValues = "MINIMAL, BASIC, FULL",
                            defaultValue = "MINIMAL")
                    @RequestParam(value = "view", required = false, defaultValue = "MINIMAL")
                    String view) {

        if (pageSize != null && (pageSize < 1 || pageSize > MAX_PAGE_SIZE)) {
            throw new InvalidPageSizeException(MAX_PAGE_SIZE);
        }
        TaskFilter filter =
                new TaskFilter(
                        namePrefix, state, TaskFilter.zipTags(asList(tagKey), asList(tagValue)));
        TesListTasksResponse response =
                this.tesService.listTasks(
                        filter, pageSize, pageToken, TaskView.fromString(view), this.getUser());
        return new ResponseEntity<TesListTasksResponse>(response, HttpStatus.OK);
    }

    private static List<String> asList(String[] values) {
        return values == null ? null : Arrays.asList(values);
    }

    private User getUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        OAuth2Authentication oauth = null;
        if (authentication instanceof OAuth2Authentication) {
            oauth = (OAuth2Authentication) authentication;
            return (User) oauth.getPrincipal();
        }
        return User.builder(authentication.getName()).build();
    }
}
