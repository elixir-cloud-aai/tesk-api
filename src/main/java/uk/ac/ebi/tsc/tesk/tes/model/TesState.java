package uk.ac.ebi.tsc.tesk.tes.model;

import java.util.Objects;
import io.swagger.annotations.ApiModel;
import com.fasterxml.jackson.annotation.JsonValue;
import javax.validation.Valid;
import javax.validation.constraints.*;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Task state as defined by the server.   - `UNKNOWN`: The state of the task is unknown. The cause for this status   message may be dependent on the underlying system. The `UNKNOWN` states   provides a safe default for messages where this field is missing so   that a missing field does not accidentally imply that   the state is QUEUED.  - `QUEUED`: The task is queued and awaiting resources to begin computing.  - `INITIALIZING`: The task has been assigned to a worker and is currently preparing to run. For example, the worker may be turning on, downloading input files, etc.  - `RUNNING`: The task is running. Input files are downloaded and the first Executor has been started.  - `PAUSED`: The task is paused. The reasons for this would be tied to   the specific system running the job. An implementation may have the ability   to pause a task, but this is not required.  - `COMPLETE`: The task has completed running. Executors have exited without error and output files have been successfully uploaded.  - `EXECUTOR_ERROR`: The task encountered an error in one of the Executor processes. Generally, this means that an Executor exited with a non-zero exit code.  - `SYSTEM_ERROR`: The task was stopped due to a system error, but not from an Executor, for example an upload failed due to network issues, the worker's ran out of disk space, etc.  - `CANCELED`: The task was canceled by the user.
 */
public enum TesState {
  
  UNKNOWN("UNKNOWN"),
  
  QUEUED("QUEUED"),
  
  INITIALIZING("INITIALIZING"),
  
  RUNNING("RUNNING"),
  
  PAUSED("PAUSED"),
  
  COMPLETE("COMPLETE"),
  
  EXECUTOR_ERROR("EXECUTOR_ERROR"),
  
  SYSTEM_ERROR("SYSTEM_ERROR"),
  
  CANCELED("CANCELED");

  private String value;

  TesState(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }

  @JsonCreator
  public static TesState fromValue(String value) {
    for (TesState b : TesState.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }
}

