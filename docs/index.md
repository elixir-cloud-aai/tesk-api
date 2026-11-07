# TESK API

TESK API is the API server of [TESK](https://github.com/elixir-cloud-aai/TESK),
an implementation of the [GA4GH Task Execution Service
(TES)](https://github.com/ga4gh/task-execution-schemas) API for
[Kubernetes](https://kubernetes.io). It accepts TES tasks, runs each of them as
Kubernetes jobs, and reports their state and logs from those jobs and their
pods. It is a Spring Boot application, published as the image
`elixircloud/tesk-api` on [Docker
Hub](https://hub.docker.com/r/elixircloud/tesk-api/tags) and deployed with the
Helm chart of TESK.

These pages cover:

- [API](api.md): the endpoints, service info and HTTP status codes;
- [Configuration](configuration.md): the settings of TESK API;
- [Access control](access-control.md): authentication with OpenID Connect and
  authorisation by group membership;
- [Deployment](deployment.md): running TESK API locally and in Kubernetes;
- [Upgrading](upgrading.md): what changes between versions;
- [Development](development.md): building, testing and releasing.

## Design

TESK uses the Kubernetes Batch API
([Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/job/)) to
run TES tasks. The state of a task is stored in, and read from, Kubernetes API
objects (jobs and pods); TESK has no database of its own.
[Persistent volume
claims](https://kubernetes.io/docs/concepts/storage/persistent-volumes/#persistentvolumeclaims)
hold the input and output files of a task while it runs, pass them between its
executors, and are deleted when the task is done.

### Creating a task

When TESK API gets a request to create a task, these steps follow:

1. TESK API creates the taskmaster, a Kubernetes job (one per task) that
   processes the inputs and outputs and runs the task's executors in turn. It
   passes the taskmaster a JSON object with the task's inputs, outputs and
   volumes (unchanged) and its executors (as Kubernetes jobs). The request
   returns at once, with the generated task ID, without waiting for the task.
2. If the task has inputs, outputs or volumes, the taskmaster creates a
   persistent volume claim (PVC) for the task.
3. The taskmaster creates the inputs filer, another Kubernetes job (one per
   task), with the PVC mounted, and maps the input, output and volume
   directories to subpaths of the PVC.
4. The inputs filer downloads the input files to their paths in the PVC.
5. For each executor, the taskmaster mounts the PVC in the same way, creates
   the executor's Kubernetes job and waits for it to complete. If the job
   fails, the taskmaster stops; otherwise it goes on with the next executor.
   Cancelling the task interrupts this loop.
6. Each executor runs its command and writes its results to the PVC (or to
   standard output only).
7. When all executors have succeeded, the taskmaster creates the outputs filer
   (one job per task), with the PVC mounted as before.
8. The outputs filer uploads the output files from the PVC to their
   destinations.
9. The taskmaster deletes the PVC and ends.

### Getting tasks

For a single task or a list of tasks, TESK API reads the Kubernetes jobs of
their taskmasters, executors and filers and the pods of those jobs, and the
logs of the taskmaster and executor pods. It matches these objects to tasks by
their [labels](https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/)
and combines them into the TES task.

The task's state comes from these jobs. In this order, the first rule that
applies gives the state:

1. The taskmaster's job is labelled as cancelled: `CANCELED`.
2. An executor's job failed, whichever executor it is: `EXECUTOR_ERROR`.
3. The taskmaster's job or the outputs filer's job failed: `SYSTEM_ERROR`.
4. The taskmaster's job completed: `COMPLETE` if the jobs of all the task's
   executors exist and completed, otherwise `SYSTEM_ERROR` (e.g., the inputs
   filer failed, so that no executor ran, or an executor's job is missing).
   The number of executors comes from the task stored with the taskmaster's
   job; without it, the executor jobs that exist count as all executors.
5. A pod of the taskmaster's job is pending: `QUEUED`.
6. The taskmaster's job is active: `INITIALIZING` until the first executor's
   job exists, `QUEUED` while a pod of the last executor's job is pending,
   otherwise `RUNNING`.
7. Otherwise: `SYSTEM_ERROR`.

A job failed if Kubernetes set its condition `Failed`, or `FailureTarget`,
which Kubernetes 1.31 and later set first. Without these conditions, a job
with failed pods and none that succeeded counts as failed once the
taskmaster's job has ended (until then, the job may still retry). A job
completed if one of its pods succeeded. The taskmaster keeps the jobs that
failed, so that their state and logs stay available, and it always ends with
exit code 0; the state doesn't depend on it.

Executor jobs aren't retried (`backoffLimit: 0`), so that the state and logs
of an executor are those of its only run; the taskmaster's environment
variable `EXECUTOR_BACKOFF_LIMIT` sets another limit (see
[Configuration](configuration.md)), which also covers evicted pods. TESK's
Helm chart sets it from `tesk.executorRetries` (2 by default). The taskmaster's own job keeps
Kubernetes' default retries: a taskmaster that is restarted, e.g. after its
pod was evicted, continues with the jobs that already exist.

### Cancelling a task

To cancel a task, TESK API labels the taskmaster's job and pod as cancelled.
The job's label gives the task the state `CANCELED`. The pod's label reaches
the taskmaster through the Downward API; when the taskmaster sees it, it stops
the running executor and ends.
