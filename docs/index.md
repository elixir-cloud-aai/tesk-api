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
  authorisation by group membership (or optionally by a claim);
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
   volumes (unchanged) and its executors (as Kubernetes jobs), in a ConfigMap
   that the job owns (see [Deleting tasks](#deleting-tasks)). TESK API first
   checks the ConfigMap in a dry run, so that a ConfigMap that Kubernetes
   refuses (e.g. one above Kubernetes' limit of 1 MiB) fails the request
   before anything is created, and a taken name is replaced by a new one. It
   then creates the job and, right after it, the ConfigMap; the taskmaster's
   pod waits until the ConfigMap exists. If the ConfigMap can't be created
   after all, TESK API deletes the job again (if the name was taken in
   between, it then tries again with a new name). If that deletion fails too,
   TESK API logs it and fails the request without trying again: the job stays
   behind, is listed as a task in state `QUEUED` whose ID the client never
   got, and its pod waits for the ConfigMap until someone deletes the job.
   The request returns at once, with the generated task ID, without waiting
   for the task.
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
which Kubernetes 1.31 and later set first. Without these conditions, an
executor's or the outputs filer's job with failed pods and none that
succeeded counts as failed once the taskmaster's job has ended (until then,
the job may still retry). A job completed if one of its pods succeeded. The
taskmaster keeps the jobs that failed, so that their state and logs stay
available, and it always ends with exit code 0; the state doesn't depend on
it.

Executor jobs aren't retried (`backoffLimit: 0`), so that the state and logs
of an executor are those of its only run. The taskmaster's environment
variable `EXECUTOR_BACKOFF_LIMIT` sets another limit (see
[Configuration](configuration.md)); TESK's Helm chart sets it from
`tesk.executorRetries` (2 by default). The limit also covers pods that
Kubernetes evicts or preempts: without retries, the executor's job fails, and
the task becomes `EXECUTOR_ERROR`, also if the executor's command didn't
fail. The taskmaster's own job keeps Kubernetes' default retries: a
taskmaster that is restarted, e.g. after its pod was evicted, continues with
the jobs that already exist. Between its retries, the task is reported as
`SYSTEM_ERROR`, also while an executor's pod is pending.

### Cancelling a task

To cancel a task, TESK API labels the taskmaster's job and pod as cancelled.
The job's label gives the task the state `CANCELED`. A task that has ended,
by the rules above, can't be cancelled (`400 Bad Request`). The pod's label
reaches the taskmaster through the Downward API; when the taskmaster sees it,
it stops the running executor and ends.

TESK API checks the state before it labels the job. If the task ends in
between, for example because an executor fails at that moment, the label
still wins and the task stays `CANCELED`.

### Deleting tasks

A task exists as long as its taskmaster's job does: TESK has no database, so
a task whose job was deleted is gone. `GET /tasks/{id}` answers `404 Not
Found` for it, and `GET /tasks` doesn't list it. TESK API counts a job that
Kubernetes is deleting (it has a deletion timestamp, e.g. while Kubernetes
waits for the job's dependents) as deleted, too, so that a task doesn't show
up as `SYSTEM_ERROR` while its jobs disappear. They are left out after
Kubernetes returned a page of taskmaster jobs, so a page can be shorter than
`page_size`, even empty, with a `next_page_token`.

Kubernetes deletes a task's objects with its taskmaster's job, through [owner
references](https://kubernetes.io/docs/concepts/overview/working-with-objects/owners-dependents/):
the ConfigMap that TESK API creates, and the filer and executor jobs (with
their pods) that the taskmaster creates. The taskmaster can set a TTL on its
own job (`TASK_TTL_SECONDS`, see the taskmaster's documentation in
[tesk-core](https://github.com/elixir-cloud-aai/tesk-core)), so that
Kubernetes deletes finished tasks after that time; without it, tasks are kept
until someone deletes their jobs. To delete a task by hand, delete its
taskmaster's job with its dependents, e.g. `kubectl delete job <task ID>`
(the default propagation policy, `background`, deletes them).
