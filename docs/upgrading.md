# Upgrading

## To the next release

Changes since 1.2.0 that clients or deployments may notice:

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
  ([tesk-core](https://github.com/elixir-cloud-aai/tesk-core)) to the release
  that keeps failed jobs (not released yet; see
  [tesk-core#98](https://github.com/elixir-cloud-aai/tesk-core/issues/98)).
  Tasks running during the upgrade keep the taskmaster they were started
  with. While only one of them is upgraded:
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
- **Non-root image.** The image runs as the user `tesk` (UID 1000, group 0)
  instead of `root`, with the home directory `/home/tesk` (see
  [Deployment](deployment.md#image)). The Helm chart of TESK already runs it
  as UID 1000 when its security context is enabled (`tesk.securityContext`,
  the default). If you run the image yourself with a kubeconfig file, mount
  it to `/home/tesk/.kube/config` instead of `/root/.kube/config` (or set
  `KUBECONFIG`), and run it with your own UID in group 0
  (`--user "$(id -u):0"`) if only your user may read the file; if you mount
  other files, make sure that the container's user or group 0 can read them.

## To 1.2.0

From 1.1.0, only deployments that use the Swagger UI have to change their
configuration. What's new:

- **Swagger UI off by default.** TESK API no longer serves the Swagger UI and
  its static resources and configuration (`/swagger-resources`) unless
  `TESK_API_SWAGGER_UI_ENABLED` is `true`; the root path (`/`) then redirects
  to it as before. While it is off, these paths and `/` answer `404 Not
  Found`. The API description (`/v2/api-docs`) is served either way. If you or
  your users rely on the Swagger UI, e.g. for its built-in OpenID Connect
  client, set the variable in your deployment (see [API](api.md#swagger-ui));
  the Helm chart of TESK has no value for it yet. Turning it on serves the
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
  it with `auth.service_info_public`, which needs TESK API 1.2.0 or later.
- **Image.** The image is built from source on Eclipse Temurin 8 (Ubuntu)
  instead of the discontinued `openjdk:8-jdk-alpine`. Releases are published
  as `elixircloud/tesk-api:X.Y.Z` (and `latest`), signed with Sigstore and
  scanned before they are published; see [Deployment](deployment.md#image).
  If you mount files into the container or run it with a fixed user ID, check
  that these still fit the new base image.
- **Service info version.** The built-in `service-info.yaml` reports version
  `1.2.0`. If you use your own file (`TESK_API_SERVICE_INFO_LOCATION`), update
  its `version` yourself.

Pre-releases (e.g. `1.2.0rc1`) are published under their own tag only and
never as `latest`.
