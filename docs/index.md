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
   that the job owns (see [Deleting tasks](#deleting-tasks)). It creates the
   job first, then any private executor image pull Secrets, then the ConfigMap.
   The taskmaster's pod waits until the ConfigMap exists (see [Failures when
   creating a task](#failures-when-creating-a-task)). The request returns at once, with
   the generated task ID, without waiting for the task.
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

#### Failures when creating a task

Before it creates the job, TESK API checks the ConfigMap and any private image
pull Secrets in dry runs, with owner references to the job that have a
placeholder UID: a resource that Kubernetes refuses (e.g. a ConfigMap above
Kubernetes' limit of 1 MiB, a name already taken, or an owner TESK API may
not set) fails the request before anything is created, and a name that is
taken is replaced by a new one. If an admission webhook for either resource
doesn't support dry runs, TESK API logs a warning and skips that check. If
creating a Secret or the ConfigMap fails after the job was created, TESK API
deletes the job again (if the name was taken in between, it then tries again
with a new name). If that deletion fails too, TESK API logs both errors and
fails the request (`500 Internal Server Error`) without trying again. The job
then stays behind: it is listed as a task in state
`QUEUED` whose ID the client never got, and its pod waits for the ConfigMap
until someone deletes the job. The same happens if the request to create the
job fails (e.g. times out) after Kubernetes has created the job.

### Getting tasks

For a single task or a list of tasks, TESK API reads the Kubernetes jobs of
their taskmasters, executors and filers and the pods of those jobs, and the
logs of the taskmaster and executor pods. It matches these objects to tasks by
their [labels](https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/)
and combines them into the TES task.

The task's state comes from these jobs. In this order, the first rule that
applies gives the state:

1. The taskmaster's job is labelled as cancelled: `CANCELING` while a job
   of the task has a pod that hasn't ended, or one that Kubernetes counts
   as active (see below); `CANCELED` once none has.
2. An executor's job failed, whichever executor it is: `PREEMPTED` if every
   failed executor's job was disrupted (see below), otherwise
   `EXECUTOR_ERROR`.
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
7. The taskmaster's job isn't active, has no pods, Kubernetes counts none
   as failed, and the task has no other jobs: `QUEUED`. This is the case right after the task was
   created, until Kubernetes' job controller has created the job's pod; on a
   busy cluster, e.g. when many tasks are created at once, that can take
   seconds. A task also stays `QUEUED` while Kubernetes can't create that pod
   at all (e.g. a `ResourceQuota` of the namespace is used up, or an
   admission policy refuses the pod; see the job's events with `kubectl
   describe job <task ID>`): if the cause doesn't go away, until someone
   deletes the job.
8. Otherwise: `SYSTEM_ERROR`.

A job failed if Kubernetes set its condition `Failed`, or `FailureTarget`,
which Kubernetes 1.31 and later set first. Without these conditions, an
executor's or the outputs filer's job with failed pods and none that
succeeded counts as failed once the taskmaster's job has ended (until then,
the job may still retry). A job completed if one of its pods succeeded. The
taskmaster keeps the jobs that failed, so that their state and logs stay
available, and it always ends with exit code 0; the state doesn't depend on
it.

For `CANCELING`, TESK API looks at the jobs of the taskmaster, the executors
and the outputs filer. A pod hasn't ended while it is pending, running, also
while it terminates, without a status yet, or in phase `Unknown`. A pod that
is still listed a minute after its deletion time (the end of its grace
period) counts as ended: it most likely ran on a node that is gone, and
Kubernetes removes it only once the node is removed (or tainted
`out-of-service`). The minute allows for clocks of TESK API and the
Kubernetes API server that differ. The inputs
filer's job isn't read: a task cancelled while it downloads inputs can be
`CANCELED` before the inputs filer's pod has ended.

Executor jobs aren't retried (`backoffLimit: 0`), so that the state and logs
of an executor are those of its only run. The taskmaster's environment
variable `EXECUTOR_BACKOFF_LIMIT` sets another limit (see
[Configuration](configuration.md)); TESK's Helm chart sets it from
`tesk.executorRetries` (2 by default). The limit also covers pods that
Kubernetes evicts or preempts: without retries, the executor's job fails.

The task is then `PREEMPTED` instead of `EXECUTOR_ERROR` if the executor's
command didn't fail: a failed executor's job counts as disrupted if at least
one of its pods has the condition `DisruptionTarget` (Kubernetes 1.26 and
later) with the status `True` and one of the reasons
`PreemptionByScheduler` (the scheduler preempted it for a pod of higher
priority), `TerminationByKubelet` (e.g. node shutdown or node pressure),
`DeletionByTaintManager` (a `NoExecute` taint) or `EvictionByEvictionAPI`
(e.g. `kubectl drain`), and every pod of the job in phase `Failed` has it. If
one executor failed on its own, the task is `EXECUTOR_ERROR`. TESK API sees
the condition only while the pod exists: Kubernetes deletes the pods that the
scheduler preempts, the taint manager or the Eviction API evict, once the
job controller has counted them, so such a pod is usually gone soon after
the job failed (pods that the kubelet terminated stay). A failed executor
whose pods are gone counts as `EXECUTOR_ERROR`.

The taskmaster's own job keeps Kubernetes' default retries: a taskmaster that
is restarted, e.g. after its pod was evicted, continues with the jobs that
already exist. Between its retries, the task is reported as `SYSTEM_ERROR`,
also while an executor's pod is pending.

### Cancelling a task

To cancel a task, TESK API labels the taskmaster's job and pod as cancelled.
The job's label gives the task the state `CANCELING`, and `CANCELED` once
none of its jobs has a pod that hasn't ended (see the rules above). A task
that has ended (`COMPLETE`, `EXECUTOR_ERROR`, `SYSTEM_ERROR`, `PREEMPTED`, or
`CANCELED`) can't be cancelled (`400 Bad Request`). Cancelling a task that is
`CANCELING` answers `200 OK`; it only labels a pending or running
taskmaster pod that doesn't have the label yet (e.g. labelling it failed
before, or Kubernetes restarted the taskmaster). The pod's label reaches the taskmaster through the
Downward API; when the taskmaster sees it, it stops the running executor and
ends.

If the taskmaster doesn't run yet (its pod is pending, or Kubernetes hasn't
created it yet), a label on a pod created later wouldn't reach it. TESK API
then labels the job as cancelled and sets its `parallelism` to 0 and its
`activeDeadlineSeconds` to 1: Kubernetes creates no more pods for the job,
removes the ones it has, and fails the job about a second later; it also
labels any pending pod. The job's label keeps the task cancelled
(`CANCELING`, then `CANCELED`). In tests on minikube (Kubernetes 1.28 and
1.34), no taskmaster cancelled this way started. A taskmaster whose container is starting at that very moment could
still start until Kubernetes stops it;
[tesk-core#132](https://github.com/elixir-cloud-aai/tesk-core/issues/132)
tracks a check for cancellation in the taskmaster itself.

TESK API checks the state before it labels the job. If the task ends in
between, for example because an executor fails at that moment, the label
still wins and the task stays cancelled (`CANCELING`, then `CANCELED`).

### Deleting tasks

A task exists as long as its taskmaster's job does: TESK has no database, so
a task whose job was deleted is gone. `GET /tasks/{id}` answers `404 Not
Found` for it, and `GET /tasks` doesn't list it. TESK API counts a job that
Kubernetes is deleting (it has a deletion timestamp, e.g. while Kubernetes
waits for the job's dependents) as deleted, too, so that a task doesn't show
up as `SYSTEM_ERROR` while its jobs disappear. TESK API leaves these jobs out
after Kubernetes has returned a page of taskmaster jobs, so a page can be shorter than
`page_size`, even empty, with a `next_page_token`.

Kubernetes deletes a task's objects with its taskmaster's job, through [owner
references](https://kubernetes.io/docs/concepts/overview/working-with-objects/owners-dependents/):
the ConfigMap and any private image pull Secrets that TESK API creates, and
the filer and executor jobs (with their pods) that the taskmaster creates.
The taskmaster can set a TTL on its own job (`TASK_TTL_SECONDS`, see the
taskmaster's documentation in
[tesk-core](https://github.com/elixir-cloud-aai/tesk-core)), so that
Kubernetes deletes finished tasks after that time; without it, tasks are kept
until someone deletes their jobs. To delete a task by hand, delete its
taskmaster's job with its dependents, e.g. `kubectl delete job <task ID>`
(the default propagation policy, `background`, deletes them).
