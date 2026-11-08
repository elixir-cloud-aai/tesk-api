# Configuration

TESK API reads its settings from
[`application.properties`](https://github.com/elixir-cloud-aai/tesk-api/blob/dev/src/main/resources/application.properties),
which any of the [ways that Spring Boot
supports](https://docs.spring.io/spring-boot/docs/2.7.x/reference/html/features.html#features.external-config)
can override. Environment variables are the usual way: a property's name in
upper case, with `_` for `.` and `-` (e.g., `TESK_API_K8S_NAMESPACE` for
`tesk.api.k8s.namespace`). In Kubernetes, set them in the deployment
(`spec.template.spec.containers[0].env`); the Helm chart of TESK sets the
common ones from its values.

## Settings

Environment variable | Meaning
--- | ---
`SPRING_PROFILES_ACTIVE` | `noauth` (default): no authentication or authorisation. `auth`: both on; see [Access control](access-control.md).
`TESK_API_K8S_NAMESPACE` | Kubernetes namespace of all the jobs that TESK API creates. Default: `default`.
`TESK_API_TASKMASTER_IMAGE_NAME` | Image of the taskmaster. Default: `eu.gcr.io/tes-wes/taskmaster`.
`TESK_API_TASKMASTER_IMAGE_VERSION` | Tag of the taskmaster image. Default: `v0.10.0`.
`TESK_API_TASKMASTER_FILER_IMAGE_NAME` | Image of the filer, passed on to the taskmaster, which creates the inputs and outputs filers with it. Default: `eu.gcr.io/tes-wes/filer`.
`TESK_API_TASKMASTER_FILER_IMAGE_VERSION` | Tag of the filer image. Default: `v0.10.0`.
`TESK_API_TASKMASTER_SERVICE_ACCOUNT_NAME` | Service account of the taskmaster jobs; it needs the rights to manage jobs, pods and persistent volume claims (e.g., the built-in `edit` role). Default: `default`.
`TESK_API_TASKMASTER_DEBUG` | `true`: more verbose taskmaster logs (in the `FULL` view of a task). Default: `false`.
`TESK_API_TASKMASTER_FTP_SECRET_NAME` | Name of a Kubernetes secret with the credentials of an FTP account, passed to the taskmaster as environment variables, for uploading outputs to that FTP server. Default: none.
`TESK_API_TASKMASTER_ENVIRONMENT_*` | Passed to the taskmaster as environment variables, without the prefix (`TESK_API_TASKMASTER_ENVIRONMENT_XXX` becomes `XXX`). The taskmaster knows, among others, `TRANSFER_PVC_NAME`, `HOST_BASE_PATH` and `CONTAINER_BASE_PATH` (exchange of inputs and outputs through a shared file system instead of FTP), and `EXECUTOR_BACKOFF_LIMIT` and `FILER_BACKOFF_LIMIT` (how often executor and filer jobs retry failed pods; executor jobs don't retry by default, see [Getting tasks](index.md#getting-tasks)).
`TESK_API_TASKMASTER_EXECUTOR_SECRET_NAME` | Name of a Kubernetes secret to mount into every executor as a volume (all its files). Default: none.
`TESK_API_TASKMASTER_EXECUTOR_SECRET_MOUNT_PATH` | Where that secret is mounted. Default: `/secret`.
`TESK_API_SERVICE_INFO_LOCATION` | The YAML file of [service info](#service-info). Default: the built-in `classpath:service-info.yaml`.
`TESK_API_SERVICE_INFO_PUBLIC` | With the `auth` profile: `true` (default) lets `GET` and `HEAD /service-info` answer without a token; `false` makes them need a valid token (no group membership). No effect with `noauth`, where all endpoints are open. Since 1.2.0.
`SECURITY_OAUTH2_RESOURCE_USER_INFO_URI` | User info endpoint of the OpenID Connect provider, which validates tokens; see [Access control](access-control.md#token-validation). Default: ELIXIR AAI.
`TESK_API_AUTHORISATION_*` | Authorisation by group membership; see [Access control](access-control.md#configuration).
`TESK_API_AUTHORISATION_LEGACY_USER_LABELS` | `true`: tasks created by earlier versions, which have only the old creator label, belong to their creators again; `false` (default): only team admins and super admins see them. See [Access control](access-control.md#task-creators).
`TESK_API_AUTHORISATION_CLAIM_*` | Authorisation without groups, by a claim of the user info; off by default. Turning it on lets everyone with one of the configured values run tasks; see [Access control](access-control.md#authorisation-without-groups) for the settings and their security implications.
`TESK_API_SWAGGER_UI_ENABLED` | `true`: serves the [Swagger UI](api.md#swagger-ui) at `/swagger-ui/index.html` (the root path `/` redirects there) with its static resources and its configuration (`/swagger-resources`). `false` (default): serves none of them. The API description (`/v2/api-docs`) is served either way. Other values stop the app at startup. Turning it on serves the Swagger UI and its static resources publicly, also with the `auth` profile: turn it on only where it is needed. Since 1.2.0; before, the Swagger UI was always on.
`TESK_API_SWAGGER_OAUTH_*` | The OpenID Connect client of the Swagger UI; see [Access control](access-control.md#built-in-client).
`TESK_API_SWAGGER_OAUTH_CLIENT_SECRET` | Deprecated since 1.2.0 and ignored: the Swagger UI is a public client and gets no client secret. TESK API logs a warning at startup if it is set. Remove it from your deployment; if a client secret was configured while the Swagger UI was on (always the case before 1.2.0, with the `auth` profile), rotate that secret at your identity provider (see [Upgrading](upgrading.md#to-120)).

## Service info

`GET /service-info` (see [API](api.md#service-info)) answers with the content
of the YAML file that `TESK_API_SERVICE_INFO_LOCATION` points to, e.g.
`file:/etc/tesk/service-info.yaml` from a ConfigMap. The built-in file
describes a test environment, so give your deployment its own: the ID, name,
organisation, contact, documentation URL, environment and storage locations
are yours to fill in. Service info is public by default, also with the `auth`
profile; if it names anything that only your users should see, set
`TESK_API_SERVICE_INFO_PUBLIC` to `false` (in the Helm chart of TESK:
`auth.service_info_public`).
