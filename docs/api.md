# API

TESK API implements the [GA4GH TES API
1.0](https://github.com/ga4gh/task-execution-schemas) under the base path
`/ga4gh/tes/v1` (setting `openapi.taskExecutionService.base-path`):

Method | Path | Operation
--- | --- | ---
`GET` | `/service-info` | Information about the service
`POST` | `/tasks` | Create a task
`GET` | `/tasks` | List tasks (in pages, optionally only those whose names start with a prefix; see [Listing tasks](#listing-tasks))
`GET` | `/tasks/{id}` | Get a task
`POST` | `/tasks/{id}:cancel` | Cancel a task (answers with an empty object, `{}`)

The API description (OpenAPI 2.0) is at `/v2/api-docs`.

With the `auth` profile, all endpoints need a valid access token, except the
API description, the Swagger UI (if it is on) and, unless
`TESK_API_SERVICE_INFO_PUBLIC` is `false`, service info; see [Access
control](access-control.md).

## Listing tasks

`GET /tasks` lists the tasks that the user may see, in the order in which
Kubernetes lists their taskmaster jobs (by task ID), in the views `MINIMAL`
(the default), `BASIC` and `FULL`.

- `page_size` sets how many tasks TESK API reads from Kubernetes for one
  page, from 1 to 2048; without it, it reads all of them at once. Other
  values, also those that aren't whole numbers, are refused with
  `400 Bad Request`. `next_page_token` is the continue token of Kubernetes;
  pass it as `page_token` to get the next page.
  It is left out on the last page. Kubernetes accepts it for a limited time
  only (by default about 5 minutes, until etcd compacts its history).
- `name_prefix` lists only the tasks whose names start with it
  (case-sensitive); tasks without a name are left out. An empty `name_prefix`
  filters nothing.

The task names aren't Kubernetes labels, so Kubernetes can't select them:
TESK API filters each page after reading it. Pages can therefore be shorter
than `page_size`, even empty, while `next_page_token` is still set; the same
happens already to users who manage some of their groups and are only
members of others, whose pages TESK API filters, too. The TES specification
doesn't forbid this: keep requesting pages until `next_page_token` is left
out, not until a page is shorter than `page_size`.

An expired or malformed `page_token` (one that Kubernetes refuses with
`410 Gone`, or with `400 Bad Request` because of the continue token) is
refused with `400 Bad Request`; the message names `page_token`, but not
its value. Start again without `page_token`. Other errors of Kubernetes,
also for forged tokens that it can't tell apart from its own failures, still
give `500 Internal Server Error`.

## Resources

`resources.cpu_cores` of a task sets both the CPU request and the CPU limit of
each executor's container. The TES specification defines it as an integer;
TESK API also accepts decimal numbers with up to 3 decimals (Kubernetes counts
CPU in thousandths of a core), e.g. `0.5` for half a core (requested from
Kubernetes as `500m`), up to `999999.999`; trailing zeros don't count
(`0.5000` is `0.5`). Numbers with more digits or decimals are refused with
`400 Bad Request`. TESK API returns the number as it was given; whole numbers
stay integers, also if given with an exponent (`1e3` is returned as `1000`).

`cpu_cores`, `ram_gb` and `disk_gb` must be positive if they are given: `0`
and negative numbers are refused with `400 Bad Request`. `ram_gb` must be at
least `0.000001`, the smallest amount that TESK API requests (it rounds RAM
to 6 decimals before it asks Kubernetes). Leave a field out instead: TESK API
then requests no CPU or RAM (the defaults of the namespace's LimitRange may
apply), and 0.1 GB for the task's volume.

## Executor logs

With the views `BASIC` and `FULL`, a task's `logs[0].logs` has one entry per
executor whose Kubernetes job exists, in the order of the executors. An
executor whose job hasn't been created yet, or never will be because the task
ended or was cancelled before, has no entry; an executor whose job exists but
whose pod is still pending has one.

- `start_time` and `end_time` are the start and completion times of the
  executor's Kubernetes job. Each is left out until Kubernetes has recorded
  it, and `start_time` can be earlier than the moment the executor's
  container started, e.g. while its pod waits for a node or an image.
- `stdout` is `""` with `BASIC`. With `FULL`, it is the log of the executor's
  pod: what the container wrote to its standard output and standard error,
  as Kubernetes keeps it. Streams that the executor redirects to files
  (`stdout` and `stderr` of the TES executor) aren't in it.

`exit_code` is left out until the executor's container has terminated, e.g.
while the executor is queued or running, or if its pod was removed before it
ended (as on cancellation). This deviates from the TES specification, which
requires `exit_code` in every executor log: clients must accept executor logs
without it.

## Output file logs

With the views `BASIC` and `FULL`, a task's `logs[0].outputs` is always an
empty array (`[]`), also after the outputs filer has uploaded the task's
outputs. The TES specification requires the field, with one entry per output
file (directory outputs flattened into their files), but the outputs filer
runs as a separate Kubernetes job and has no way to report the files it
wrote back to TESK API. Clients that need the uploaded files must look them
up at the outputs' `url`s.

## Swagger UI

The Swagger UI lists the endpoints and lets you try them, also with a token
from its built-in OpenID Connect client (see [Access
control](access-control.md#built-in-client)). It is off by default; set
`TESK_API_SWAGGER_UI_ENABLED` to `true` to serve it, with its static
resources and its configuration (`/swagger-resources`), at
`/swagger-ui/index.html`, to which the root path (`/`) then redirects (see
[Configuration](configuration.md)). While it is off, these paths answer `404
Not Found`.

## Service info

`GET /service-info` describes the service, following the [GA4GH Service
Info](https://github.com/ga4gh-discovery/ga4gh-service-info) schema: its ID,
name, type (`org.ga4gh:TES`, version `1.0.0`: TESK API implements TES
1.0.0), organisation, contact and documentation URLs, version and the storage
locations it supports. The content comes from a YAML
file, set by `TESK_API_SERVICE_INFO_LOCATION` (by default the built-in
[`service-info.yaml`](https://github.com/elixir-cloud-aai/tesk-api/blob/dev/src/main/resources/service-info.yaml)).
Set it to a file of your deployment, e.g. from a ConfigMap: the built-in one
describes a test environment.

By default, service info is public, also with the `auth` profile, so that
registries and clients can see what a service offers and how to get access.
To require a token for it, set `TESK_API_SERVICE_INFO_PUBLIC` to `false` (see
[Configuration](configuration.md#service-info)).

## HTTP status codes

The TES specification doesn't define its error responses. TESK API answers
with these status codes:

Status code | Meaning
--- | ---
200 | OK: successful request
400 | Bad request: validation error, or cancellation of a task that doesn't run
401 | Unauthorized: no access token in the request, or an invalid token
403 | Forbidden: the user may not do this (usually, not in the required group, or without the required claim)
404 | Not found: no task with this ID
500 | Something else went wrong (possibly the configuration)
