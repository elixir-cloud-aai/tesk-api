package uk.ac.ebi.tsc.tesk.k8s.constant;

import io.kubernetes.client.custom.V1Patch;
import java.util.EnumSet;
import uk.ac.ebi.tsc.tesk.tes.model.TesState;

/**
 * TESK implementation specific constants
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
public class Constants {

    /** ENV var that serves as taskmaster script input (JSON format) */
    public static final String TASKMASTER_INPUT = "JSON_INPUT";

    /** Key in JSON taskmaster input, which holds list of executors */
    public static final String TASKMASTER_INPUT_EXEC_KEY = "executors";

    /** */
    public static final String VOLUME_NAME = "PVC";

    /** Number of attempts of job creation in case of name collision */
    public static final int JOB_CREATE_ATTEMPTS_NO = 5;

    /** Constant prefix of taskmaster's job name (== TES task ID) */
    public static final String JOB_NAME_TASKM_PREFIX = "task-";
    /** Part of executor's job name, that follows taskmaster's name */
    public static final String JOB_NAME_EXEC_PREFIX = "-ex-";
    /** No of bytes of random part of task master's name (which end up encoded to hex) */
    public static final int JOB_NAME_TASKM_RAND_PART_LENGTH = 4;
    /**
     * No of digits reserved for executor number in executor's job name. Ends up padded with '0' for
     * numbers < 10
     */
    public static final int JOB_NAME_EXEC_NO_LENGTH = 2;

    /** Output filer name suffix */
    public static final String JOB_NAME_FILER_SUF = "-outputs-filer";

    /**
     * Key of the annotation, that stores name of TES task in both taskmaster's job and executor's
     * jobs.
     */
    public static final String ANN_TESTASK_NAME_KEY = "tes-task-name";

    /** Key of the annotation, that stores whole input TES task serialized to JSON */
    public static final String ANN_JSON_INPUT_KEY = "json-input";

    /**
     * Key of the annotation of the taskmaster's job with the keys of the task's backend parameters,
     * which TESK doesn't support and therefore doesn't store (a JSON array of strings)
     */
    public static final String ANN_IGNORED_BACKEND_PARAMETERS_KEY = "ignored-backend-parameters";

    /**
     * Key of the label, that stores taskmaster's name (==TES task generated ID) in executor jobs
     */
    public static final String LABEL_TESTASK_ID_KEY = "taskmaster-name";

    /** Key of the label, that stores type of a job (taskmaster or executor) */
    public static final String LABEL_JOBTYPE_KEY = "job-type";

    /** Value of the label with taskmaster's job type */
    public static final String LABEL_JOBTYPE_VALUE_TASKM = "taskmaster";

    /** Value of the label with executor's job type */
    public static final String LABEL_JOBTYPE_VALUE_EXEC = "executor";

    /** Key of the label, that holds executor number for executor jobs */
    public static final String LABEL_EXECNO_KEY = "executor-no";

    /** Key of the label, that holds executor's state */
    public static final String LABEL_TASKSTATE_KEY = "task-status";

    /** Value of the label, that holds executor's Cancelled state */
    public static final String LABEL_TASKSTATE_VALUE_CANC = "Cancelled";

    /**
     * Key of the old label with the creating user's ID, with {@code @} replaced by {@code _}. New
     * tasks don't get it; it is read for tasks created before {@link #LABEL_USERHASH_KEY} (see
     * {@code tesk.api.authorisation.legacy-user-labels}). Same key as {@link #ANN_USERID_KEY},
     * which is an annotation, not a label
     */
    public static final String LABEL_LEGACY_USERID_KEY = "creator-user-id";

    /**
     * Key of the label with a hash of the creating user's full ID (see {@link
     * uk.ac.ebi.tsc.tesk.k8s.convert.CreatorLabels}), by which the user's tasks are selected
     */
    public static final String LABEL_USERHASH_KEY = "creator-user-hash";

    /**
     * Key of the annotation with the creating user's full ID (same key as the old label {@link
     * #LABEL_LEGACY_USERID_KEY})
     */
    public static final String ANN_USERID_KEY = "creator-user-id";

    /** Key of the label, that holds user's group name */
    public static final String LABEL_GROUPNAME_KEY = "creator-group-name";

    /** Pattern to validate paths */
    public static final String ABSOLUTE_PATH_REGEXP = "^\\/.*";

    /** Message for absolute path validation (to avoid message.properties) */
    public static final String ABSOLUTE_PATH_MESSAGE = "must be an absolute path";

    public static final double RESOURCE_DISK_DEFAULT = 0.1;

    /** TES task states, indicating task is not running and cannot be cancelled */
    public static EnumSet<TesState> COMPLETED_STATES =
            EnumSet.of(
                    TesState.CANCELED,
                    TesState.COMPLETE,
                    TesState.EXECUTOR_ERROR,
                    TesState.SYSTEM_ERROR);

    /** Name of taskmaster's ENV variable with username of FTP account used for storage */
    public static final String FTP_SECRET_USERNAME_ENV = "TESK_FTP_USERNAME";
    /** Name of taskmaster's ENV variable with password of FTP account used for storage */
    public static final String FTP_SECRET_PASSWORD_ENV = "TESK_FTP_PASSWORD";

    /** Patch object passed to job API, when cancelling task */
    public static final V1Patch CANCEL_PATCH =
            new V1Patch(
                    "{\"metadata\":{\"labels\":{\""
                            + LABEL_TASKSTATE_KEY
                            + "\":\""
                            + LABEL_TASKSTATE_VALUE_CANC
                            + "\"}}}");

    /**
     * Cancels a taskmaster's job whose pod doesn't run yet: the cancel label, parallelism 0, so
     * that Kubernetes creates no pods and removes the job's pods at once, and an active deadline of
     * 1 second, after which Kubernetes fails the job (issue #126)
     */
    public static final V1Patch CANCEL_BEFORE_START_PATCH =
            new V1Patch(
                    "{\"metadata\":{\"labels\":{\""
                            + LABEL_TASKSTATE_KEY
                            + "\":\""
                            + LABEL_TASKSTATE_VALUE_CANC
                            + "\"}},\"spec\":{\"parallelism\":0,\"activeDeadlineSeconds\":1}}");
    /** Patch object passed to pod API, when cancelling task */
    // public static final V1Pod POD_CANCEL_PATCH = new V1Pod().metadata(new
    // V1ObjectMeta().putLabelsItem(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC));

}
