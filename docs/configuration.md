# Configuration

TESK API reads its settings from
[`application.properties`](https://github.com/elixir-cloud-aai/tesk-api/blob/dev/src/main/resources/application.properties),
which any of the [ways that Spring Boot
supports](https://docs.spring.io/spring-boot/reference/features/external-config.html)
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
`TESK_API_TASKMASTER_IMAGE_NAME` | Image of the taskmaster. Default: `docker.io/elixircloud/tesk-core-taskmaster`.
`TESK_API_TASKMASTER_IMAGE_VERSION` | Tag of the taskmaster image. Default: `0.11.0`.
`TESK_API_TASKMASTER_FILER_IMAGE_NAME` | Image of the filer, passed on to the taskmaster, which creates the inputs and outputs filers with it. Default: `docker.io/elixircloud/tesk-core-filer`.
`TESK_API_TASKMASTER_FILER_IMAGE_VERSION` | Tag of the filer image. Default: `0.11.0`.
`TESK_API_TASKMASTER_SERVICE_ACCOUNT_NAME` | Service account of the taskmaster jobs; it needs the rights to manage jobs, pods and persistent volume claims (e.g., the built-in `edit` role). Default: `default`.
`TESK_API_TASKMASTER_DEBUG` | `true`: more verbose taskmaster logs (in the `FULL` view of a task). Default: `false`.
`TESK_API_TASKMASTER_FTP_SECRET_NAME` | Name of a Kubernetes secret with the credentials of an FTP account, passed to the taskmaster as environment variables, for uploading outputs to that FTP server. Default: none.
`TESK_API_TASKMASTER_ENVIRONMENT_*` | Passed to the taskmaster as environment variables, without the prefix (`TESK_API_TASKMASTER_ENVIRONMENT_XXX` becomes `XXX`). The taskmaster knows, among others, `TRANSFER_PVC_NAME`, `HOST_BASE_PATH` and `CONTAINER_BASE_PATH` (exchange of inputs and outputs through a shared file system instead of FTP), and `EXECUTOR_BACKOFF_LIMIT` and `FILER_BACKOFF_LIMIT` (how often executor and filer jobs retry failed pods; executor jobs don't retry by default, see [Getting tasks](index.md#getting-tasks)).
`TESK_API_TASKMASTER_EXECUTOR_SECRET_NAME` | Name of a Kubernetes secret to mount into every executor as a volume (all its files). Default: none.
`TESK_API_TASKMASTER_EXECUTOR_SECRET_MOUNT_PATH` | Where that secret is mounted. Default: `/secret`.
`CONFIGMAP` | Name of a ConfigMap with the pod security context of the executors, which TESK API reads at startup. Its value at the key `CONFIGMAPKEY` (default `securityContext`) is a YAML map with any of `fsGroup`, `runAsUser`, `runAsGroup` (numbers) and `nonRoot` (for `runAsNonRoot`: `true`, in any case; any other value counts as `false`); a ConfigMap without that key gives an empty security context. TESK API doesn't start if the ConfigMap can't be read, has no data, or its value isn't a YAML map (e.g. is empty), or if `fsGroup`, `runAsUser` or `runAsGroup` is empty or not a whole number. `CONFIGMAPNAMESPACE` is its namespace (default: that of `TESK_API_K8S_NAMESPACE`). Default: none, and the executors' pods get no security context from TESK API.
`TESK_API_SERVICE_INFO_LOCATION` | The YAML file of [service info](#service-info). Default: the built-in `classpath:service-info.yaml`.
`TESK_API_SERVICE_INFO_PUBLIC` | With the `auth` profile: `true` (default) lets `GET` and `HEAD /service-info` answer without a token; `false` makes them need a valid token (no group membership). No effect with `noauth`, where all endpoints are open. Since 1.2.0.
`SECURITY_OAUTH2_RESOURCE_USER_INFO_URI` | User info endpoint of the OpenID Connect provider, which validates tokens; see [Access control](access-control.md#token-validation). Required with the `auth` profile: TESK API doesn't start unless it is an absolute `http` or `https` URL. The name comes from Spring Security OAuth, which TESK API no longer uses; it is the only one of its `SECURITY_OAUTH2_*` settings that TESK API reads. Default: ELIXIR AAI.
`TESK_API_AUTHORISATION_*` | Authorisation by group membership: `TESK_API_AUTHORISATION_GROUPS_CLAIM` (default `eduperson_entitlement`), `…_GROUP_PREFIX`, `…_GROUP_SUFFIX`, `…_DELIMITER`, `…_PARENT_GROUP`, `…_ENV_SUBGROUP`, `…_BASE_GROUP` and `…_ADMIN_SUBGROUP`; see [Access control](access-control.md#configuration) for their meanings and defaults.
`TESK_API_AUTHORISATION_LEGACY_USER_LABELS` | `true`: tasks created by earlier versions, which have only the old creator label, belong to their creators again; `false` (default): only team admins and super admins see them. See [Access control](access-control.md#task-creators). In the Helm chart of TESK: `auth.legacyUserLabels` (from chart 0.3.0).
`TESK_API_AUTHORISATION_CLAIM_*` | Authorisation without groups, by a claim of the user info; off by default. Turning it on lets everyone with one of the configured values run tasks; see [Access control](access-control.md#authorisation-without-groups) for the settings and their security implications.
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_*` | Access tokens of allow-listed service clients (OAuth 2.0 client credentials grant, e.g. of a gateway), checked locally against the issuer's keys instead of at the user info endpoint; off by default. Every holder of a listed client's credentials can run tasks; see [Access control](access-control.md#tokens-of-services) for the settings and their trust implications. Since 2.0.0.
`TESK_API_SWAGGER_UI_ENABLED` | `true`: serves the [Swagger UI](api.md#swagger-ui) at `/swagger-ui/index.html` (the root path `/` redirects there) with its static resources and its configuration (`/v3/api-docs/swagger-config`). `false` (default): serves none of them. The API description (`/v3/api-docs`) is served either way. Other values stop the app at startup. Turning it on serves the Swagger UI and its static resources publicly, also with the `auth` profile: turn it on only where it is needed. Since 1.2.0; before, the Swagger UI was always on.
`TESK_API_SWAGGER_OAUTH_*` | The OpenID Connect client of the Swagger UI, with the `auth` profile: `TESK_API_SWAGGER_OAUTH_CLIENT_ID`, `…_IMPLICIT` (default `true`: the implicit grant; `false`: the authorisation code grant without a client secret), `…_AUTHORIZATION_ENDPOINT`, `…_TOKEN_ENDPOINT` and `…_SCOPES`; see [Access control](access-control.md#built-in-client) for their meanings and defaults, and what the authorisation code grant needs.
`TESK_API_SWAGGER_OAUTH_CLIENT_SECRET` | Deprecated since 1.2.0 and ignored: the Swagger UI is a public client and gets no client secret. TESK API logs a warning at startup if it is set. Remove it from your deployment; if a client secret was configured while the Swagger UI was on (always the case before 1.2.0, with the `auth` profile), rotate that secret at your identity provider (see [Upgrading](upgrading.md#to-120)).
`TESK_API_CORS_ALLOWED_ORIGINS` | Origins of browser clients that may call TESK API directly (cross-origin requests, CORS), comma-separated, each exactly as the browser sends it (scheme, host and port if not the default, e.g. `https://client.example.org,http://localhost:3000`); host names in ASCII (punycode for international ones, as browsers send them) without `_`; no wildcards, paths, user info, default ports (`:443`, `:80`) or ports with leading zeros, which stop the app at startup. Default: none, and answers carry no CORS headers. See [Cross-origin requests](#cross-origin-requests-cors).
`TESK_API_TRS_URIPATTERN` | Pattern of executor images that TESK API looks up in a [GA4GH Tool Registry Service](#tool-images-from-a-tool-registry) (TRS). Default: `trs://{host}/{id}/versions/{version}`.
`TESK_API_TRS_URLPATTERN` | URL that TESK API asks for such an image. Default: `https://{host}/ga4gh/trs/v2/tools/{id}/versions/{version}`.
`SERVER_FORWARD_HEADERS_STRATEGY` | How TESK API learns its public URL behind a reverse proxy (e.g. for the server URL in the [API description](api.md), which the Swagger UI uses for "Try it out"). `native` (default): the embedded Tomcat takes scheme, host and port from the `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-Port` headers, and the client address from `X-Forwarded-For`, but only of requests from internal addresses (Spring Boot's default: `10.0.0.0/8, 192.168.0.0/16, 169.254.0.0/16, 100.64.0.0/10, fc00::/7, 172.16.0.0/12, ::1/128, 127.0.0.0/8, fe80::/10`); headers from other addresses are ignored. If your proxy has another address, set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to a comma-separated list of CIDR ranges that contains it, and no addresses of untrusted clients. `none`: ignores the headers. Not in 2.0.0, which honours the headers only in Kubernetes; up to 1.2.0, Springfox read `X-Forwarded-Host` for the API description itself.

## Tool images from a tool registry

An executor's `image` can name a tool version in a GA4GH Tool Registry
Service instead of a container image. If it matches `TESK_API_TRS_URIPATTERN`
(e.g. `trs://example.org/my-tool/versions/1.0`), TESK API takes `{host}`,
`{id}` and `{version}` from it, sends `GET` to `TESK_API_TRS_URLPATTERN`
with these values filled in (here
`https://example.org/ga4gh/trs/v2/tools/my-tool/versions/1.0`), and runs the
first Docker image of the answer. If the request fails or the answer has no
Docker image, the executor gets the `image` as given. The host comes from
the task, so TESK API sends these requests to any host that a task names;
limit its outgoing network access (e.g. with a Kubernetes network policy) if
your deployment needs that.

## Service info

`GET /service-info` (see [API](api.md#service-info)) answers with the content
of the YAML file that `TESK_API_SERVICE_INFO_LOCATION` points to, e.g.
`file:/etc/tesk/service-info.yaml` from a ConfigMap. The built-in file
describes a test environment, so give your deployment its own: the ID, name,
organisation, contact, documentation URL, environment and storage locations
are yours to fill in. `tesResources_backend_parameters` may be left out or
set to `[]`: TESK supports no backend parameters (see [TES 1.1
fields](api.md#tes-11-fields)), so service info always reports `[]`, and
TESK API logs a warning if the file lists any.

Service info is public by default, also with the `auth` profile; if it
names anything that only your users should see, set
`TESK_API_SERVICE_INFO_PUBLIC` to `false` (in the Helm chart of TESK:
`auth.serviceInfoPublic`).

## Cross-origin requests (CORS)

Browsers let a web app call TESK API from another origin only if TESK API
allows it with CORS headers. TESK API sends none by default, so clients that
call it from their servers (or through a gateway such as proTES) need no
setting. To let a browser app call TESK API directly, list the app's origins
in `TESK_API_CORS_ALLOWED_ORIGINS`, e.g.:

```yaml
env:
  - name: TESK_API_CORS_ALLOWED_ORIGINS
    value: https://client.example.org,https://staging.client.example.org
```

With allowed origins, in both profiles:

- Preflight requests (`OPTIONS` with `Origin` and
  `Access-Control-Request-Method`) of the allowed origins are answered
  without a token, also with the `auth` profile, as browsers send them
  without one. They allow the methods `GET`, `HEAD` and `POST`, the request
  headers `Authorization` and `Content-Type`, and are cached by browsers for
  30 minutes.
- Answers to the requests of the allowed origins carry
  `Access-Control-Allow-Origin` with that origin, also errors such as
  `401 Unauthorized`, and expose the `WWW-Authenticate` header to the app.
  Every request still needs a token as before, sent by the app in the
  `Authorization` header; CORS changes only what browsers let the app read.
- Requests and preflight requests with an `Origin` of any other origin,
  requests of other methods, and preflight requests that ask for other
  methods or request headers are refused with `403 Forbidden`. Requests
  without `Origin` (e.g. of scripts and servers) are handled as before.
- Credentials (cookies, `Access-Control-Allow-Credentials`) aren't allowed:
  TESK API uses none.

Origins are compared exactly (case aside, and a trailing `/` of a configured
origin is ignored), so list every origin that you serve the app from, e.g.
with and without `www.`, and leave out default ports, which browsers don't
send (`https://client.example.org`, not `https://client.example.org:443`). There are no wildcards: `*` would let any website
call TESK API from its users' browsers. Browsers send `Origin` also with
some requests to TESK API's own origin (e.g. `POST` from the Swagger UI);
TESK API recognises them as same-origin only if it knows its public URL
(see `SERVER_FORWARD_HEADERS_STRATEGY`), else it refuses them like those of
other origins once CORS is on. See also [Access
control](access-control.md#browser-clients-cors).
