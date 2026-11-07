# API

TESK API implements the [GA4GH TES API
1.0](https://github.com/ga4gh/task-execution-schemas) under the base path
`/ga4gh/tes/v1` (setting `openapi.taskExecutionService.base-path`):

Method | Path | Operation
--- | --- | ---
`GET` | `/service-info` | Information about the service
`POST` | `/tasks` | Create a task
`GET` | `/tasks` | List tasks (in pages; the name prefix filter is not supported yet and is ignored)
`GET` | `/tasks/{id}` | Get a task
`POST` | `/tasks/{id}:cancel` | Cancel a task (answers with an empty object, `{}`)

The API description (OpenAPI 2.0) is at `/v2/api-docs`.

With the `auth` profile, all endpoints need a valid access token, except the
API description, the Swagger UI (if it is on) and, unless
`TESK_API_SERVICE_INFO_PUBLIC` is `false`, service info; see [Access
control](access-control.md).

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
name, type (`org.ga4gh:TES`), organisation, contact and documentation URLs,
version and the storage locations it supports. The content comes from a YAML
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
403 | Forbidden: the user may not do this (usually, not in the required group)
404 | Not found: no task with this ID
500 | Something else went wrong (possibly the configuration)
