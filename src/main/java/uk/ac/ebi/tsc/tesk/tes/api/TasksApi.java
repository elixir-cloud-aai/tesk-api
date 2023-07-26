/**
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech) (5.1.0).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */
package uk.ac.ebi.tsc.tesk.tes.api;

import uk.ac.ebi.tsc.tesk.tes.model.TesCreateTaskResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesListTasksResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;
import io.swagger.annotations.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.multipart.MultipartFile;
import springfox.documentation.annotations.ApiIgnore;

import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
@javax.annotation.Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2023-07-25T15:44:45.116897+02:00[Europe/Prague]")
@Validated
@Api(value = "tasks", description = "the tasks API")
public interface TasksApi {

    default Optional<NativeWebRequest> getRequest() {
        return Optional.empty();
    }

    /**
     * POST /tasks/{id}:cancel : CancelTask
     * Cancel a task based on providing an exact task ID.
     *
     * @param id ID of task to be canceled. (required)
     * @return  (status code 200)
     */
    @ApiOperation(value = "CancelTask", nickname = "cancelTask", notes = "Cancel a task based on providing an exact task ID.", response = Object.class, tags={ "TaskService", })
    @ApiResponses(value = { 
        @ApiResponse(code = 200, message = "", response = Object.class) })
    @PostMapping(
        value = "/tasks/{id}:cancel",
        produces = { "application/json" }
    )
    default ResponseEntity<Object> cancelTask(@ApiParam(value = "ID of task to be canceled.",required=true) @PathVariable("id") String id) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }


    /**
     * POST /tasks : CreateTask
     * Create a new task. The user provides a Task document, which the server uses as a basis and adds additional fields.
     *
     * @param body  (required)
     * @return  (status code 200)
     */
    @ApiOperation(value = "CreateTask", nickname = "createTask", notes = "Create a new task. The user provides a Task document, which the server uses as a basis and adds additional fields.", response = TesCreateTaskResponse.class, tags={ "TaskService", })
    @ApiResponses(value = { 
        @ApiResponse(code = 200, message = "", response = TesCreateTaskResponse.class) })
    @PostMapping(
        value = "/tasks",
        produces = { "application/json" },
        consumes = { "application/json" }
    )
    default ResponseEntity<TesCreateTaskResponse> createTask(@ApiParam(value = "" ,required=true )  @Valid @RequestBody TesTask body) {
        getRequest().ifPresent(request -> {
            for (MediaType mediaType: MediaType.parseMediaTypes(request.getHeader("Accept"))) {
                if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                    String exampleString = "{ \"id\" : \"id\" }";
                    ApiUtil.setExampleResponse(request, "application/json", exampleString);
                    break;
                }
            }
        });
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }


    /**
     * GET /tasks/{id} : GetTask
     * Get a single task, based on providing the exact task ID string.
     *
     * @param id ID of task to retrieve. (required)
     * @param view OPTIONAL. Affects the fields included in the returned Task messages.  &#x60;MINIMAL&#x60;: Task message will include ONLY the fields: - &#x60;tesTask.Id&#x60; - &#x60;tesTask.State&#x60;  &#x60;BASIC&#x60;: Task message will include all fields EXCEPT: - &#x60;tesTask.ExecutorLog.stdout&#x60; - &#x60;tesTask.ExecutorLog.stderr&#x60; - &#x60;tesInput.content&#x60; - &#x60;tesTaskLog.system_logs&#x60;  &#x60;FULL&#x60;: Task message includes all fields. (optional, default to MINIMAL)
     * @return  (status code 200)
     */
    @ApiOperation(value = "GetTask", nickname = "getTask", notes = "Get a single task, based on providing the exact task ID string.", response = TesTask.class, tags={ "TaskService", })
    @ApiResponses(value = { 
        @ApiResponse(code = 200, message = "", response = TesTask.class) })
    @GetMapping(
        value = "/tasks/{id}",
        produces = { "application/json" }
    )
    default ResponseEntity<TesTask> getTask(@ApiParam(value = "ID of task to retrieve.",required=true) @PathVariable("id") String id,@ApiParam(value = "OPTIONAL. Affects the fields included in the returned Task messages.  `MINIMAL`: Task message will include ONLY the fields: - `tesTask.Id` - `tesTask.State`  `BASIC`: Task message will include all fields EXCEPT: - `tesTask.ExecutorLog.stdout` - `tesTask.ExecutorLog.stderr` - `tesInput.content` - `tesTaskLog.system_logs`  `FULL`: Task message includes all fields.", allowableValues = "MINIMAL, BASIC, FULL", defaultValue = "MINIMAL") @Valid @RequestParam(value = "view", required = false, defaultValue="MINIMAL") String view) {
        getRequest().ifPresent(request -> {
            for (MediaType mediaType: MediaType.parseMediaTypes(request.getHeader("Accept"))) {
                if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                    String exampleString = "{ \"outputs\" : [ { \"path\" : \"/data/outfile\", \"url\" : \"s3://my-object-store/outfile-1\", \"type\" : \"FILE\" } ], \"creation_time\" : \"2020-10-02T10:00:00-05:00\", \"inputs\" : [ { \"url\" : \"s3://my-object-store/file1\", \"path\" : \"/data/file1\" } ], \"name\" : \"name\", \"volumes\" : [ \"/vol/A/\" ], \"description\" : \"description\", \"resources\" : { \"preemptible\" : false, \"cpu_cores\" : 4, \"zones\" : \"us-west-1\", \"ram_gb\" : 8, \"disk_gb\" : 40 }, \"executors\" : [ { \"image\" : \"ubuntu:20.04\", \"stdin\" : \"/data/file1\", \"workdir\" : \"/data/\", \"stdout\" : \"/tmp/stdout.log\", \"stderr\" : \"/tmp/stderr.log\", \"env\" : { \"BLASTDB\" : \"/data/GRC38\", \"HMMERDB\" : \"/data/hmmer\" }, \"command\" : [ \"/bin/md5\", \"/data/file1\" ] }, { \"image\" : \"ubuntu:20.04\", \"stdin\" : \"/data/file1\", \"workdir\" : \"/data/\", \"stdout\" : \"/tmp/stdout.log\", \"stderr\" : \"/tmp/stderr.log\", \"env\" : { \"BLASTDB\" : \"/data/GRC38\", \"HMMERDB\" : \"/data/hmmer\" }, \"command\" : [ \"/bin/md5\", \"/data/file1\" ] } ], \"id\" : \"job-0012345\", \"state\" : \"COMPLETE\", \"logs\" : [ { \"outputs\" : [ { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" }, { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" } ], \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"metadata\" : { \"host\" : \"worker-001\", \"slurmm_id\" : 123456 }, \"system_logs\" : [ \"system_logs\", \"system_logs\" ], \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"logs\" : [ { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" }, { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" } ] }, { \"outputs\" : [ { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" }, { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" } ], \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"metadata\" : { \"host\" : \"worker-001\", \"slurmm_id\" : 123456 }, \"system_logs\" : [ \"system_logs\", \"system_logs\" ], \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"logs\" : [ { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" }, { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" } ] } ], \"tags\" : { \"WORKFLOW_ID\" : \"cwl-01234\", \"PROJECT_GROUP\" : \"alice-lab\" } }";
                    ApiUtil.setExampleResponse(request, "application/json", exampleString);
                    break;
                }
            }
        });
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }


    /**
     * GET /tasks : ListTasks
     * List tasks tracked by the TES server. This includes queued, active and completed tasks. How long completed tasks are stored by the system may be dependent on the underlying implementation.
     *
     * @param namePrefix OPTIONAL. Filter the list to include tasks where the name matches this prefix. If unspecified, no task name filtering is done. (optional)
     * @param pageSize Optional number of tasks to return in one page. Must be less than 2048. Defaults to 256. (optional)
     * @param pageToken OPTIONAL. Page token is used to retrieve the next page of results. If unspecified, returns the first page of results. The value can be found in the &#x60;next_page_token&#x60; field of the last returned result of ListTasks (optional)
     * @param view OPTIONAL. Affects the fields included in the returned Task messages.  &#x60;MINIMAL&#x60;: Task message will include ONLY the fields: - &#x60;tesTask.Id&#x60; - &#x60;tesTask.State&#x60;  &#x60;BASIC&#x60;: Task message will include all fields EXCEPT: - &#x60;tesTask.ExecutorLog.stdout&#x60; - &#x60;tesTask.ExecutorLog.stderr&#x60; - &#x60;tesInput.content&#x60; - &#x60;tesTaskLog.system_logs&#x60;  &#x60;FULL&#x60;: Task message includes all fields. (optional, default to MINIMAL)
     * @return  (status code 200)
     */
    @ApiOperation(value = "ListTasks", nickname = "listTasks", notes = "List tasks tracked by the TES server. This includes queued, active and completed tasks. How long completed tasks are stored by the system may be dependent on the underlying implementation.", response = TesListTasksResponse.class, tags={ "TaskService", })
    @ApiResponses(value = { 
        @ApiResponse(code = 200, message = "", response = TesListTasksResponse.class) })
    @GetMapping(
        value = "/tasks",
        produces = { "application/json" }
    )
    default ResponseEntity<TesListTasksResponse> listTasks(@ApiParam(value = "OPTIONAL. Filter the list to include tasks where the name matches this prefix. If unspecified, no task name filtering is done.") @Valid @RequestParam(value = "name_prefix", required = false) String namePrefix,@ApiParam(value = "Optional number of tasks to return in one page. Must be less than 2048. Defaults to 256.") @Valid @RequestParam(value = "page_size", required = false) Long pageSize,@ApiParam(value = "OPTIONAL. Page token is used to retrieve the next page of results. If unspecified, returns the first page of results. The value can be found in the `next_page_token` field of the last returned result of ListTasks") @Valid @RequestParam(value = "page_token", required = false) String pageToken,@ApiParam(value = "OPTIONAL. Affects the fields included in the returned Task messages.  `MINIMAL`: Task message will include ONLY the fields: - `tesTask.Id` - `tesTask.State`  `BASIC`: Task message will include all fields EXCEPT: - `tesTask.ExecutorLog.stdout` - `tesTask.ExecutorLog.stderr` - `tesInput.content` - `tesTaskLog.system_logs`  `FULL`: Task message includes all fields.", allowableValues = "MINIMAL, BASIC, FULL", defaultValue = "MINIMAL") @Valid @RequestParam(value = "view", required = false, defaultValue="MINIMAL") String view) {
        getRequest().ifPresent(request -> {
            for (MediaType mediaType: MediaType.parseMediaTypes(request.getHeader("Accept"))) {
                if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                    String exampleString = "{ \"next_page_token\" : \"next_page_token\", \"tasks\" : [ { \"outputs\" : [ { \"path\" : \"/data/outfile\", \"url\" : \"s3://my-object-store/outfile-1\", \"type\" : \"FILE\" } ], \"creation_time\" : \"2020-10-02T10:00:00-05:00\", \"inputs\" : [ { \"url\" : \"s3://my-object-store/file1\", \"path\" : \"/data/file1\" } ], \"name\" : \"name\", \"volumes\" : [ \"/vol/A/\" ], \"description\" : \"description\", \"resources\" : { \"preemptible\" : false, \"cpu_cores\" : 4, \"zones\" : \"us-west-1\", \"ram_gb\" : 8, \"disk_gb\" : 40 }, \"executors\" : [ { \"image\" : \"ubuntu:20.04\", \"stdin\" : \"/data/file1\", \"workdir\" : \"/data/\", \"stdout\" : \"/tmp/stdout.log\", \"stderr\" : \"/tmp/stderr.log\", \"env\" : { \"BLASTDB\" : \"/data/GRC38\", \"HMMERDB\" : \"/data/hmmer\" }, \"command\" : [ \"/bin/md5\", \"/data/file1\" ] }, { \"image\" : \"ubuntu:20.04\", \"stdin\" : \"/data/file1\", \"workdir\" : \"/data/\", \"stdout\" : \"/tmp/stdout.log\", \"stderr\" : \"/tmp/stderr.log\", \"env\" : { \"BLASTDB\" : \"/data/GRC38\", \"HMMERDB\" : \"/data/hmmer\" }, \"command\" : [ \"/bin/md5\", \"/data/file1\" ] } ], \"id\" : \"job-0012345\", \"state\" : \"COMPLETE\", \"logs\" : [ { \"outputs\" : [ { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" }, { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" } ], \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"metadata\" : { \"host\" : \"worker-001\", \"slurmm_id\" : 123456 }, \"system_logs\" : [ \"system_logs\", \"system_logs\" ], \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"logs\" : [ { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" }, { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" } ] }, { \"outputs\" : [ { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" }, { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" } ], \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"metadata\" : { \"host\" : \"worker-001\", \"slurmm_id\" : 123456 }, \"system_logs\" : [ \"system_logs\", \"system_logs\" ], \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"logs\" : [ { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" }, { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" } ] } ], \"tags\" : { \"WORKFLOW_ID\" : \"cwl-01234\", \"PROJECT_GROUP\" : \"alice-lab\" } }, { \"outputs\" : [ { \"path\" : \"/data/outfile\", \"url\" : \"s3://my-object-store/outfile-1\", \"type\" : \"FILE\" } ], \"creation_time\" : \"2020-10-02T10:00:00-05:00\", \"inputs\" : [ { \"url\" : \"s3://my-object-store/file1\", \"path\" : \"/data/file1\" } ], \"name\" : \"name\", \"volumes\" : [ \"/vol/A/\" ], \"description\" : \"description\", \"resources\" : { \"preemptible\" : false, \"cpu_cores\" : 4, \"zones\" : \"us-west-1\", \"ram_gb\" : 8, \"disk_gb\" : 40 }, \"executors\" : [ { \"image\" : \"ubuntu:20.04\", \"stdin\" : \"/data/file1\", \"workdir\" : \"/data/\", \"stdout\" : \"/tmp/stdout.log\", \"stderr\" : \"/tmp/stderr.log\", \"env\" : { \"BLASTDB\" : \"/data/GRC38\", \"HMMERDB\" : \"/data/hmmer\" }, \"command\" : [ \"/bin/md5\", \"/data/file1\" ] }, { \"image\" : \"ubuntu:20.04\", \"stdin\" : \"/data/file1\", \"workdir\" : \"/data/\", \"stdout\" : \"/tmp/stdout.log\", \"stderr\" : \"/tmp/stderr.log\", \"env\" : { \"BLASTDB\" : \"/data/GRC38\", \"HMMERDB\" : \"/data/hmmer\" }, \"command\" : [ \"/bin/md5\", \"/data/file1\" ] } ], \"id\" : \"job-0012345\", \"state\" : \"COMPLETE\", \"logs\" : [ { \"outputs\" : [ { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" }, { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" } ], \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"metadata\" : { \"host\" : \"worker-001\", \"slurmm_id\" : 123456 }, \"system_logs\" : [ \"system_logs\", \"system_logs\" ], \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"logs\" : [ { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" }, { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" } ] }, { \"outputs\" : [ { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" }, { \"path\" : \"path\", \"size_bytes\" : \"[\"1024\"]\", \"url\" : \"url\" } ], \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"metadata\" : { \"host\" : \"worker-001\", \"slurmm_id\" : 123456 }, \"system_logs\" : [ \"system_logs\", \"system_logs\" ], \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"logs\" : [ { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" }, { \"start_time\" : \"2020-10-02T10:00:00-05:00\", \"stdout\" : \"stdout\", \"end_time\" : \"2020-10-02T11:00:00-05:00\", \"exit_code\" : 0, \"stderr\" : \"stderr\" } ] } ], \"tags\" : { \"WORKFLOW_ID\" : \"cwl-01234\", \"PROJECT_GROUP\" : \"alice-lab\" } } ] }";
                    ApiUtil.setExampleResponse(request, "application/json", exampleString);
                    break;
                }
            }
        });
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }

}
