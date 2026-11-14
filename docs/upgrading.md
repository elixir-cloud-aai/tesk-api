# Upgrading

## To the next release

From 1.2.0, deployments may have to check their JVM options, and clients may
notice changed answers:

- **Java 21.** The image runs TESK API on Java 21 instead of Java 8 (see
  [Deployment](deployment.md)). If you pass options to the JVM in
  `JAVA_OPTS`, check that Java 21 still accepts them: options that it
  removed (e.g. `-XX:MaxPermSize`, `-XX:+UseConcMarkSweepGC`,
  `-XX:+UseCGroupMemoryLimitForHeap`) stop the JVM
  at startup. Without `JAVA_OPTS`, nothing changes.
- **Inputs without a source.** An input with neither a `url` (that isn't
  blank) nor a `content` (that isn't empty) is still refused with `400 Bad
  Request`, but the message now names the check:
  `Field 'inputs[0].urlOrContentGiven' URL or content required` instead of
  `Field 'inputs[0]' URL or content required`. The messages are meant for
  people; clients should rely on the status code, not on their text.
- **Wildcards without `path_prefix`.** An output whose `path` has wildcards
  (`*`, `?`, `[`) but no `path_prefix` is refused with `400 Bad Request`,
  unless its `url` is a `file:` URL or has no scheme, as TES 1.1 requires
  `path_prefix` for such outputs (see [TES 1.1 fields](api.md#tes-11-fields)).
  Before, such a task ran, and its output then failed or uploaded nothing
  useful. Clients that send such outputs to other URLs set `path_prefix`,
  e.g. to the part of `path` before its first wildcard.
- **Default page size.** `GET /tasks` without `page_size` returns at most
  256 tasks per page, as TES specifies, with a `next_page_token` if there
  are more (see [Listing tasks](api.md#listing-tasks)). Before, it returned
  all tasks at once. Clients that don't pass `page_size` must follow
  `next_page_token`, or pass a `page_size` of up to 2048.
- **`ignore_error`.** `executors[].ignore_error: true` is accepted rather
  than refused with `400 Bad Request` (see [TES 1.1
  fields](api.md#tes-11-fields)). It needs a taskmaster that goes on after
  such an executor fails
  ([tesk-core#142](https://github.com/elixir-cloud-aai/tesk-core/issues/142)):
  upgrade the taskmaster image first (`TESK_API_TASKMASTER_IMAGE_VERSION`,
  see [Configuration](configuration.md)). An older taskmaster stops at the
  failure: the task then ends `SYSTEM_ERROR`, unless the failed executor was
  the last one and the task has no outputs, which completes as asked.

## To 1.2.0

From 1.1.0, deployments may have to change their configuration, the rights
of TESK API in Kubernetes and the order of the upgrade, and clients may
notice changed answers: read all of the items below before you upgrade.
What's new:

- **Creator labels.** New tasks record their creator in the label
  `creator-user-hash` (a hash of the user's `sub`) and the annotation
  `creator-user-id` (the `sub` itself) instead of the label
  `creator-user-id` (the `sub` with `@` replaced by `_`), so that any `sub`
  works, also one longer than 63 characters or with characters that labels
  don't allow, and different IDs are told apart (see [Task
  creators](access-control.md#task-creators)). `logs[0].metadata.USER_ID`
  shows the `sub` unchanged (e.g. `user@example.org` instead of
  `user_example.org`). Tasks created before the upgrade keep the old label:
  team admins and super admins see them as before, but their creators can no
  longer list, get or cancel them. What to do:
    - Upgrade when no tasks run, or let running tasks end first; if you want
      their creators to keep access for a while, set
      `TESK_API_AUTHORISATION_LEGACY_USER_LABELS=true` (in the Helm chart
      of TESK: `auth.legacyUserLabels`, from chart 0.3.0). Turn it off again
      once the old tasks
      are no longer needed (e.g. after the taskmaster's `TASK_TTL_SECONDS`).
    - List the old tasks with `kubectl get jobs -n <namespace> -l
      'job-type=taskmaster,creator-user-id,!creator-user-hash'`. The old
      label can't be converted exactly, because its value doesn't tell
      whether a `_` was an `@`. (Kubernetes can't select by annotations:
      an object that also has the annotation `creator-user-id` shows up
      here too, but TESK API doesn't count it as old.) Delete the old tasks that are no longer
      needed by name (`kubectl delete jobs -n <namespace> <name>`).
      `kubectl delete jobs -n <namespace> -l
      'creator-user-id,!creator-user-hash'` deletes all old tasks at once,
      running ones included, with their executors' jobs, which carry the
      same label; `kubectl delete configmaps -n <namespace> -l
      'creator-user-id,!creator-user-hash'` deletes their ConfigMaps (see
      "Deleted tasks" below).
    - With several replicas of TESK API, a rolling update runs old and new
      replicas side by side for a while: each one doesn't match the tasks
      created by the other to their creators, so creators can get `403
      Forbidden` for their newest tasks until the update completes. Update
      with the `Recreate` strategy to avoid that, or accept the short
      window.
    - The taskmaster (tesk-core) doesn't read these labels: it copies the
      executors' jobs, with their labels and annotations, as TESK API
      prepares them, so it needs no change.
    - An older TESK API (e.g. after a rollback) doesn't match tasks created
      by this one to their creators, as it doesn't know the new label.

- **Task states from all jobs.** The state of a task comes from all its jobs
  (see [Getting tasks](index.md#getting-tasks)). An executor's job that
  failed, whichever executor it is, gives `EXECUTOR_ERROR`, also with the
  job condition `FailureTarget` (Kubernetes 1.31 and later). A task is
  `COMPLETE` only if the jobs of all its executors completed; if one is
  missing, the task is `SYSTEM_ERROR`. Before, the state came from the last
  executor job that existed, so that a task whose last executor's job was
  deleted could be `COMPLETE`.
- **Executors aren't retried.** Executor jobs get `backoffLimit: 0` (before:
  Kubernetes' default of 6 retries), unless the taskmaster's environment sets
  `EXECUTOR_BACKOFF_LIMIT`. TESK's Helm chart sets it from
  `tesk.executorRetries` (2 by default), so chart deployments keep that
  limit; set it to 0 for single runs.
- **Cancelling ended tasks.** `POST /tasks/{id}:cancel` reads all jobs of
  the task, as `GET` does, so a task whose executor failed while the
  taskmaster still runs can't be cancelled any more (`400 Bad Request`).
- **Upgrade order.** Upgrade TESK API first, then the taskmaster
  ([tesk-core](https://github.com/elixir-cloud-aai/tesk-core)) to v0.11.0 or
  later, which keeps failed jobs (see
  [tesk-core#98](https://github.com/elixir-cloud-aai/tesk-core/issues/98)).
  The Helm chart of TESK upgrades both at once (from chart 0.3.0, which sets
  both versions); tasks running during the upgrade still keep the taskmaster
  they were started with. While only one of them is upgraded:
    - This TESK API with an older taskmaster reports a task whose last
      executor's job the taskmaster deleted (because its pod couldn't pull
      its image) as `SYSTEM_ERROR`, instead of `COMPLETE`.
    - An older TESK API with the newer taskmaster reports a failed executor
      as `EXECUTOR_ERROR`. For a few seconds after Kubernetes sets
      `FailureTarget`, before it counts the failed pod, the older TESK API
      can report `QUEUED` or `SYSTEM_ERROR`; clients that take
      `SYSTEM_ERROR` as final keep it.
- **Output file logs.** With the views `BASIC` and `FULL`, a task's
  `logs[0].outputs` is an empty array (`[]`), as the TES specification
  requires the field; before, it was left out. TESK API still doesn't list
  the uploaded files (see [Output file logs](api.md#output-file-logs)).
  `logs` sent with a new task are ignored. Before, they came back in front
  of TESK's own log: `BASIC` and `FULL` showed them as `logs[0]`, the access
  check of `GET /tasks/{id}` read the user and group from them (so the
  creator of a task could show it to other users or hide it from the
  managers of its group), and a task whose sent log had no `metadata`
  failed with `500`.
- **Filtering tasks by name.** `GET /tasks` supports `name_prefix` (see
  [Listing tasks](api.md#listing-tasks)); before, it was ignored. Pages
  can be shorter than `page_size`, even empty, with a `next_page_token`.
- **Paging parameters checked.** `GET /tasks` refuses a `page_size` below 1
  or above 2048 with `400 Bad Request` (see [Listing
  tasks](api.md#listing-tasks)); before, any number was accepted, and
  numbers beyond the range of an `int` wrapped around. An expired or
  malformed `page_token` is refused with `400 Bad Request` instead of `500
  Internal Server Error`, with a message that names the parameter but not
  its value. Clients that page with more than 2048 tasks need to lower
  `page_size`. A request parameter of the wrong type (e.g. `page_size=abc`)
  is refused, as before, with `400 Bad Request`, but its message now names
  the parameter instead of repeating the value.
- **Task cancellation response.** `POST /tasks/{id}:cancel` answers a
  successful cancellation with an empty JSON object (`{}`, `Content-Type:
  application/json`), as the TES specification defines, instead of an empty
  body. Clients that checked for an empty body need to accept `{}`.
- **Decimal CPU cores.** `resources.cpu_cores` accepts decimal numbers with
  up to 3 decimals, e.g. `0.5`, up to `999999.999` (see
  [API](api.md#resources)). Before, the decimal part was dropped silently, so
  that `0.5` set no CPU at all (`0`). Whole numbers up to `999999` work as
  before; numbers with more digits or decimals are refused with `400 Bad
  Request`. The API description (`/v2/api-docs`) now gives `cpu_cores` as a
  number instead of an integer.
- **Positive resources (behaviour change).** `resources.cpu_cores`,
  `ram_gb` and `disk_gb` must be positive, and `ram_gb` at least `0.000001`:
  `0`, negative and smaller numbers are refused with `400 Bad Request` (see
  [API](api.md#resources)). Before, TESK API didn't check them and passed
  them on to Kubernetes and the taskmaster. **A task with `0` for any of
  them, which was accepted before, is now refused**: clients that send `0`
  for "no requirement" need to leave the field out instead.
- **New tasks are queued.** Right after it was created, until Kubernetes
  has created the pod of its taskmaster's job, a task is `QUEUED`; before, it
  was `SYSTEM_ERROR` for that time, so that clients that polled a new task
  at once could take it for failed (see [Getting
  tasks](index.md#getting-tasks)). In one test on minikube, with 50 tasks
  created at once, this took about 4.5 seconds. A task whose taskmaster pod
  Kubernetes can't create (e.g. a used-up `ResourceQuota`, or a pod that an
  admission policy refuses) is `QUEUED` too now, instead of `SYSTEM_ERROR`,
  until the cause goes away or someone deletes the job: if you alert on
  `SYSTEM_ERROR` for such problems, watch the jobs' `FailedCreate` events
  instead.
- **Cancelling tasks that haven't started.** A task whose taskmaster isn't
  running yet (its pod is pending, or its job has no pod yet) can be
  cancelled: TESK API labels the job (and a pending pod) as cancelled and
  sets the job's `parallelism` to 0 and `activeDeadlineSeconds` to 1, so that
  Kubernetes removes its pods and fails the job (see [Cancelling a
  task](index.md#cancelling-a-task); this uses `patch` on `jobs`, which TESK
  API already needs). Before, a task with a pending pod was reported
  `CANCELED`, but its taskmaster started later all the same; a new task
  without a pod couldn't be cancelled (`400 Bad Request`). A taskmaster whose
  container is starting at the very moment of the request could still start
  until Kubernetes stops it
  ([tesk-core#132](https://github.com/elixir-cloud-aai/tesk-core/issues/132)).
- **Jobs without labels.** `GET /tasks/{id}` and `POST /tasks/{id}:cancel`
  (the requests that read a single task) answer `404 Not Found` for the name
  of a job without labels in the namespace, as for any job that isn't a
  taskmaster's job; before, they answered `500 Internal Server Error`.
- **RAM under any locale.** Tasks with `resources.ram_gb` are created also
  when the JVM's default locale writes numbers with a decimal comma (e.g.
  `de_DE`); before, creating them failed.
- **Kubernetes client logs.** `KubernetesClientWrapper` logs at `INFO`,
  like the rest of TESK API, so the calls of its Kubernetes client are no
  longer logged; before, it was set to `TRACE`, which logged its calls with
  their arguments and the objects it created or labelled (the tasks'
  Kubernetes objects, e.g. commands and environment variables). To log them
  again, for debugging, set
  `LOGGING_LEVEL_UK_AC_EBI_TSC_TESK_K8S_SERVICE=TRACE` (Spring Boot lowercases
  logger names from environment variables, so name the package, not the
  class).
- **Authorisation without groups.** With the `auth` profile, TESK API can
  authorise users by a claim of the user info instead of group membership
  (`TESK_API_AUTHORISATION_CLAIM_ENABLED`, `TESK_API_AUTHORISATION_CLAIM_NAME`
  and `TESK_API_AUTHORISATION_CLAIM_VALUES`; see [Authorisation without
  groups](access-control.md#authorisation-without-groups)). It is off by
  default, so nothing changes unless you turn it on; read its security
  implications first. The Helm chart of TESK sets them with `auth.claim`
  (`enabled`, `name` and `values`; from chart 0.3.0).
- **Group names matched literally.** When TESK API recognises team and team
  admin groups, it compares the group settings
  (`TESK_API_AUTHORISATION_GROUP_PREFIX`, `…_PARENT_GROUP`,
  `…_ENV_SUBGROUP`, `…_BASE_GROUP`, `…_DELIMITER`, `…_ADMIN_SUBGROUP` and
  `…_GROUP_SUFFIX`) as plain text (see [Access
  control](access-control.md#configuration)). Before, they were used as
  regular expressions, so that characters such as the `.` in the default
  prefix and suffix matched any character. With plain group names, as in the
  defaults, nothing changes. If a setting relied on a pattern (e.g. `(A|B)`
  or `.*`), it no longer matches: set the exact names instead.
- **Non-root image.** The image runs as the user `tesk` (UID 1000, group 0)
  instead of `root`, with the home directory `/home/tesk` (see
  [Deployment](deployment.md#image)). The Helm chart of TESK already runs it
  as UID 1000 when its security context is enabled (`tesk.securityContext`,
  the default). If you run the image yourself with a kubeconfig file, mount
  it to `/home/tesk/.kube/config` instead of `/root/.kube/config` (or set
  `KUBECONFIG`), and run it with your own UID in group 0
  (`--user "$(id -u):0"`) if only your user may read the file; if you mount
  other files, make sure that the container's user or group 0 can read them.
- **Deleted tasks.** The taskmaster's job owns the task's ConfigMap, so that
  Kubernetes deletes the ConfigMap with the job, e.g. after the TTL that the
  taskmaster can set (`TASK_TTL_SECONDS` of
  [tesk-core](https://github.com/elixir-cloud-aai/tesk-core)). TESK API now
  checks the ConfigMap in a dry run, then creates the job and the ConfigMap
  right after it; the job's pod waits for the ConfigMap. If the ConfigMap
  can't be created after all, TESK API deletes the job. It therefore needs
  two more rights (see [Deployment](deployment.md#kubernetes)), which TESK's
  Helm chart and Kubernetes' `edit` role already grant: `delete` on `jobs`,
  and `delete` on `configmaps` where the admission plugin
  `OwnerReferencesPermissionEnforcement` is on (e.g. on OpenShift). If you
  give TESK API its own role, add them before you upgrade: without `delete`
  on `configmaps` there, no task can be created (the request fails before
  the job is created); without `delete` on `jobs`, a job whose ConfigMap
  failed stays behind (see [Failures when creating a
  task](index.md#failures-when-creating-a-task)). Tasks whose
  job Kubernetes is deleting (it has a deletion timestamp) are gone: `GET
  /tasks/{id}` answers `404 Not Found` and `GET /tasks` leaves them out (see
  [Deleting tasks](index.md#deleting-tasks)); before, they could show up as
  `SYSTEM_ERROR` until Kubernetes removed the job. ConfigMaps of tasks
  created before the upgrade have no owner and stay until you delete them
  (e.g. those whose taskmaster's job no longer exists).
- **Swagger UI off by default.** TESK API no longer serves the Swagger UI and
  its static resources and configuration (`/swagger-resources`) unless
  `TESK_API_SWAGGER_UI_ENABLED` is `true`; the root path (`/`) then redirects
  to it as before. While it is off, these paths and `/` answer `404 Not
  Found`. The API description (`/v2/api-docs`) is served either way. If you or
  your users rely on the Swagger UI, e.g. for its built-in OpenID Connect
  client, set the variable in your deployment (see [API](api.md#swagger-ui));
  the Helm chart of TESK sets it with `tesk.swaggerUi.enabled` (from chart
  0.3.0). Turning it on serves the
  Swagger UI and its static resources publicly, also with the `auth` profile:
  turn it on only where it is needed.
- **Swagger UI client secret.** The Swagger UI is a public client: TESK API
  no longer passes it a client secret (in `/swagger-resources`), and
  `TESK_API_SWAGGER_OAUTH_CLIENT_SECRET` is deprecated and ignored; TESK API
  logs a warning at startup if it is set. Remove the setting, and register
  the Swagger UI's client at your identity provider as a public client
  (implicit grant, the default, or authorisation code without a secret; the
  latter needs an identity provider that allows it for public clients without
  PKCE). If a client secret was configured while the Swagger UI was on (always
  the case before 1.2.0, with the `auth` profile), rotate that secret at your
  identity provider.
- **Service info setting.** `TESK_API_SERVICE_INFO_PUBLIC` (`true` by
  default) keeps `GET` and `HEAD /service-info` public with the `auth`
  profile, as before; set it to `false` to require a valid token for them (see
  [Configuration](configuration.md#service-info)). The Helm chart of TESK sets
  it with `auth.serviceInfoPublic` (from chart 0.3.0), which needs TESK API
  1.2.0 or later.
- **TES version in service info.** The built-in `service-info.yaml` reports
  the type version as `"1.0.0"` instead of `"1.0"`. If you use your own
  file, quote its `type.version` (e.g. `version: '1.0.0'`): unquoted, YAML
  reads values like `1.0` as numbers.
- **Image.** The image is built from source on Eclipse Temurin 8 (Ubuntu)
  instead of the discontinued `openjdk:8-jdk-alpine`. Releases are published
  as `elixircloud/tesk-api:X.Y.Z` (and `latest`), signed with Sigstore and
  scanned before they are published; see [Deployment](deployment.md#image).
  If you mount files into the container or run it with a fixed user ID, check
  that these still fit the new base image.
- **Taskmaster and filer images.** Without other settings, TESK API runs
  the taskmaster `docker.io/elixircloud/tesk-core-taskmaster:0.11.0` and the
  filer `docker.io/elixircloud/tesk-core-filer:0.11.0`, the images that
  tesk-core publishes, instead of `eu.gcr.io/tes-wes/taskmaster:v0.10.0` and
  `eu.gcr.io/tes-wes/filer:v0.10.0` (see
  [Configuration](configuration.md)). The Helm chart of TESK sets its own.
- **Service info version.** The built-in `service-info.yaml` reports version
  `1.2.0`. If you use your own file (`TESK_API_SERVICE_INFO_LOCATION`), update
  its `version` yourself.

Pre-releases (e.g. `1.2.0rc1`) are published under their own tag only and
never as `latest`.
