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
`SPRING_PROFILES_ACTIVE` | `auth` (default since 2.1.0): authentication and authorisation on; TESK API doesn't start without an issuer (`TESK_API_AUTH_ISSUERS_0_URL`); see [Access control](access-control.md). `noauth`: neither, every caller is `anonymousUser`; only by explicit choice, for installations that nobody else can reach (logged as a warning at startup). Before 2.1.0, `noauth` was the default.
`TESK_API_K8S_NAMESPACE` | Kubernetes namespace of all the jobs that TESK API creates. Default: `default`.
`TESK_API_TASKMASTER_IMAGE_NAME` | Image of the taskmaster. Default: `docker.io/elixircloud/tesk-core-taskmaster`.
`TESK_API_TASKMASTER_IMAGE_VERSION` | Tag of the taskmaster image. Default: `0.13.0` (before 2.1.0: `0.11.0`).
`TESK_API_TASKMASTER_FILER_IMAGE_NAME` | Image of the filer, passed on to the taskmaster, which creates the inputs and outputs filers with it. Default: `docker.io/elixircloud/tesk-core-filer`.
`TESK_API_TASKMASTER_FILER_IMAGE_VERSION` | Tag of the filer image. Default: `0.13.0` (before 2.1.0: `0.11.0`).
`TESK_API_TASKMASTER_SERVICE_ACCOUNT_NAME` | Service account of the taskmaster jobs; it needs the rights to manage jobs, pods and persistent volume claims (e.g., the built-in `edit` role). Default: `default`.
`TESK_API_TASKMASTER_DEBUG` | `true`: more verbose taskmaster logs (in the `FULL` view of a task). Default: `false`.
`TESK_API_TASKMASTER_FTP_SECRET_NAME` | Name of a Kubernetes secret with the credentials of an FTP account, passed to the taskmaster as environment variables, for uploading outputs to that FTP server. Default: none.
`TESK_API_TASKMASTER_ENVIRONMENT_*` | Passed to the taskmaster as environment variables, without the prefix (`TESK_API_TASKMASTER_ENVIRONMENT_XXX` becomes `XXX`). The taskmaster knows, among others, `TRANSFER_PVC_NAME`, `HOST_BASE_PATH` and `CONTAINER_BASE_PATH` (exchange of inputs and outputs through a shared file system instead of FTP), and `EXECUTOR_BACKOFF_LIMIT` and `FILER_BACKOFF_LIMIT` (how often executor and filer jobs retry failed pods; executor jobs don't retry by default, see [Getting tasks](index.md#getting-tasks)).
`TESK_API_TASKMASTER_EXECUTOR_SECRET_NAME` | Name of a Kubernetes secret to mount into every executor as a volume (all its files). Default: none.
`TESK_API_TASKMASTER_EXECUTOR_SECRET_MOUNT_PATH` | Where that secret is mounted. Default: `/secret`.
`CONFIGMAP` | Name of a ConfigMap with the pod security context of the executors, which TESK API reads at startup. Its value at the key `CONFIGMAPKEY` (default `securityContext`) is a YAML map with any of `fsGroup`, `runAsUser`, `runAsGroup` (numbers) and `nonRoot` (for `runAsNonRoot`: `true`, in any case; any other value counts as `false`, also an empty string such as `nonRoot: ''`); a ConfigMap without that key gives an empty security context. TESK API doesn't start if the ConfigMap can't be read, has no data, or its value isn't a YAML map (e.g. is empty), or if `fsGroup`, `runAsUser`, `runAsGroup` or `nonRoot` has no value (e.g. `nonRoot:`), or `fsGroup`, `runAsUser` or `runAsGroup` isn't a whole number. `CONFIGMAPNAMESPACE` is its namespace (default: that of `TESK_API_K8S_NAMESPACE`). Default: none, and the executors' pods get no security context from it (but from `TESK_API_TASKMASTER_EXECUTOR_POD_SECURITY_CONTEXT`, if set).
`TESK_API_TASKMASTER_POD_SECURITY_CONTEXT`, `TESK_API_TASKMASTER_SECURITY_CONTEXT` | Security context of the taskmaster's pod and of its container, as JSON objects with the fields of Kubernetes' `PodSecurityContext` and `SecurityContext` (e.g. `{"runAsNonRoot": true}`). Default: none, and an empty value or `{}` sets none. See [Pod security and resources](#pod-security-and-resources). Since 2.1.0.
`TESK_API_TASKMASTER_RESOURCES` | Resource requests and limits of the taskmaster's container, as a JSON object with the fields of Kubernetes' `ResourceRequirements`. Default: `{"requests": {"cpu": "100m", "memory": "128Mi"}, "limits": {"cpu": "500m", "memory": "256Mi"}}`; an empty value or `{}` sets none, as before 2.1.0. Since 2.1.0.
`TESK_API_TASKMASTER_EXECUTOR_POD_SECURITY_CONTEXT` | Security context of the executors' pods, as a JSON object with the fields of Kubernetes' `PodSecurityContext`, on top of the one from `CONFIGMAP`: its fields replace those of the ConfigMap (objects such as `seccompProfile` as a whole), the ConfigMap's other fields stay, and a field set to `null` removes that of the ConfigMap. Default: none, and an empty value or `{}` adds nothing. Since 2.1.0.
`TESK_API_TASKMASTER_EXECUTOR_SECURITY_CONTEXT` | Security context of the executors' containers, as a JSON object with the fields of Kubernetes' `SecurityContext`. Default: none, and an empty value or `{}` sets none. Since 2.1.0.
`TESK_API_TASKMASTER_EXECUTOR_DEFAULT_CPU` | CPU request and limit of the executors of tasks that set no `resources.cpu_cores`, as a Kubernetes quantity (e.g. `1`, `0.5` or `500m`). Default: `1`; an empty value sets none, as before 2.1.0. Since 2.1.0.
`TESK_API_SERVICE_INFO_LOCATION` | The YAML file of [service info](#service-info). Default: the built-in `classpath:service-info.yaml`.
`TESK_API_SERVICE_INFO_PUBLIC` | With the `auth` profile: `true` (default) lets `GET` and `HEAD /service-info` answer without a token; `false` makes them need a valid token (any valid token). No effect with `noauth`, where all endpoints are open. Since 1.2.0.
`SECURITY_OAUTH2_RESOURCE_USER_INFO_URI` | Ignored since 2.1.0: TESK API checks tokens itself, against the keys of the issuers (`TESK_API_AUTH_*` below), and asks a user info endpoint only for claims for the decision service (`TESK_API_AUTH_ISSUERS_<i>_USERINFOURI`). Up to 2.0.0, the user info endpoint that validated user tokens. Remove it from your deployment.
`TESK_API_AUTHORISATION_*` | Removed (any key under `tesk.api.authorisation`): the settings of the group model (`…_GROUPS_CLAIM`, `…_GROUP_PREFIX`, `…_GROUP_SUFFIX`, `…_DELIMITER`, `…_PARENT_GROUP`, `…_ENV_SUBGROUP`, `…_BASE_GROUP`, `…_ADMIN_SUBGROUP`, `…_CLAIM_*`, `…_LEGACY_USER_LABELS`, `…_TASK_VISIBILITY`), and those of the service clients (`…_SERVICE_CLIENTS_*`, replaced by the service clients of each issuer, `TESK_API_AUTH_ISSUERS_<i>_SERVICECLIENTS_*`). With one of them set, in any profile, TESK API doesn't start, and names it with what replaces it, if anything. Every caller gets, lists and cancels its own tasks only; see [Access control](access-control.md#authorisation) and [Upgrading](upgrading.md#to-210). Since 2.1.0.
`TESK_API_AUTH_ISSUERS_<i>_URL` | With the `auth` profile: an issuer whose access tokens TESK API accepts, exactly as in their `iss` (`https://`; `http://` only to the local host); at least one is required. Issuers are numbered from `0`. Since 2.1.0. See [Settings](access-control.md#settings).
`TESK_API_AUTH_ISSUERS_<i>_AUDIENCES` | Client IDs of the front ends through which users get tokens for TESK, comma-separated (or one per `…_AUDIENCES_<k>`): a user's token needs one of them in its `aud`. Required unless the issuer lists only service clients. Since 2.1.0.
`TESK_API_AUTH_ISSUERS_<i>_SERVICECLIENTS_<j>_CLIENTID` | A service client of the issuer whose own tokens (client credentials grant) are accepted, with its own client ID as audience; see [Tokens of services](access-control.md#tokens-of-services). Every holder of a listed client's credentials acts as that client. Default: none. Since 2.1.0; replaces `TESK_API_AUTHORISATION_SERVICE_CLIENTS_*`.
`TESK_API_AUTH_ISSUERS_<i>_SERVICECLIENTS_<j>_ALLOWANYAUDIENCE` | `true`: that client's own tokens are accepted whatever their `aud`; for testing or while setting up a federation only (see [Trust implications](access-control.md#trust-implications)). Default: `false`. Since 2.1.0.
`TESK_API_AUTH_ISSUERS_<i>_JWKSURI` | JWKS URI of the issuer, if its keys shouldn't come from its OpenID Connect discovery document. Default: from discovery. Since 2.1.0.
`TESK_API_AUTH_ISSUERS_<i>_CLIENTCLAIM` | Claim that names a token's client (some providers use `azp`). Default: `client_id`. Since 2.1.0.
`TESK_API_AUTH_ISSUERS_<i>_USERINFOURI` | User info endpoint of the issuer, where TESK API looks up claims for the decision service (`TESK_API_AUTHZ_DECISIONSERVICE_CLAIMS`) that aren't in the token; `https://` only (plain `http://` only to the local host). Default: from discovery. Since 2.1.0.
`TESK_API_AUTH_TOKEN_VALIDATOR` | How tokens are checked: only `jwt` (JWT access tokens, RFC 9068). Default: `jwt`. Since 2.1.0.
`TESK_API_AUTH_ALGORITHMS` | Allowed signature algorithms, comma-separated, asymmetric ones only. Default: `RS256,ES256`. Since 2.1.0.
`TESK_API_AUTH_REQUIRE_ACCESS_TOKEN_TYPE` | `true`: a token's header `typ` must be `at+jwt`; `false` only for providers that don't set it. Default: `true`. Since 2.1.0.
`TESK_API_AUTH_CLOCK_SKEW_SECONDS` | Leeway for `exp`, `nbf` and `iat`, in seconds. Default: `60`. Since 2.1.0.
`TESK_API_AUTH_MAX_TOKEN_AGE_SECONDS` | Maximum age of a token since its `iat`, in seconds. Default: `86400`. Since 2.1.0.
`TESK_API_AUTH_TIMEOUT_SECONDS` | Timeouts of the requests to the issuers, from 1 to 60 seconds: connect and read timeouts for discovery and keys, and the time of each user info request (discovery, lookup) as a whole. Default: `10`. Since 2.1.0.
`TESK_API_AUTHZ_DECISIONSERVICE_URL` | With the `auth` profile, the AuthZEN Access Evaluation endpoint (e.g. [tes-authz](https://github.com/elixir-cloud-aai/tes-authz)) that decides who may create tasks; see [Decision service](access-control.md#decision-service). `https://` only (plain `http://` only to the local host). Required with the `auth` profile; not allowed with `noauth`. Since 2.1.0.
`TESK_API_AUTHZ_DECISIONSERVICE_TIMEOUT` | Timeout of a request to the decision service, from 1 to 60 seconds; without a decision in time, creating a task gives `503` (and so do switched-on operations that fail closed). Default: `2`. Since 2.1.0.
`TESK_API_AUTHZ_DECISIONSERVICE_CLAIMS` | Claims of the caller that the decision service gets, comma-separated (or one per `…_CLAIMS_<k>`), from the token or else from the user info endpoint (e.g. `eduperson_entitlement` for LS Login). Default: none. Since 2.1.0.
`TESK_API_AUTHZ_DECISIONSERVICE_CREDENTIALFILE` | File with a bearer credential for the decision service (one line of printable ASCII without spaces, read for every request). Default: none. Since 2.1.0.
`TESK_API_AUTHZ_OPERATIONS` | With the `auth` profile, the operations that the decision service decides: `create` (always) and any of `get`, `list`, `cancel`, comma-separated (or one per `…_OPERATIONS_<k>`); see [Further operations](access-control.md#further-operations). Without `create`, with an unknown operation or one named twice, TESK API doesn't start. Default: `create`. Since 2.1.0.
`TESK_API_AUTHZ_FAILURE_<OPERATION>` | What a failed decision of the operation (`CREATE`, `GET`, `LIST`, `CANCEL`) does: `closed`, `503`; or `open`, TESK API's own owner-only rule, for `GET`, `LIST` and `CANCEL` only (`TESK_API_AUTHZ_FAILURE_CREATE=open`, another value or another operation stops the app at startup). Default: `closed`. Since 2.1.0.
`TESK_API_SWAGGER_UI_ENABLED` | `true`: serves the [Swagger UI](api.md#swagger-ui) at `/swagger-ui/index.html` (the root path `/` redirects there) with its static resources and its configuration (`/v3/api-docs/swagger-config`). `false` (default): serves none of them. The API description (`/v3/api-docs`) is served either way. Other values stop the app at startup. Turning it on serves the Swagger UI and its static resources publicly, also with the `auth` profile: turn it on only where it is needed. Since 1.2.0; before, the Swagger UI was always on.
`TESK_API_SWAGGER_OAUTH_*` | The OpenID Connect client of the Swagger UI, with the `auth` profile: `TESK_API_SWAGGER_OAUTH_CLIENT_ID`, `…_IMPLICIT` (default `true`: the implicit grant; `false`: the authorisation code grant without a client secret), `…_AUTHORIZATION_ENDPOINT`, `…_TOKEN_ENDPOINT` and `…_SCOPES`; see [Access control](access-control.md#built-in-client) for their meanings and defaults, and what the authorisation code grant needs.
`TESK_API_SWAGGER_OAUTH_CLIENT_SECRET` | Deprecated since 1.2.0 and ignored: the Swagger UI is a public client and gets no client secret. TESK API logs a warning at startup if it is set. Remove it from your deployment; if a client secret was configured while the Swagger UI was on (always the case before 1.2.0, with the `auth` profile), rotate that secret at your identity provider (see [Upgrading](upgrading.md#to-120)).
`TESK_API_CORS_ALLOWED_ORIGINS` | Origins of browser clients that may call TESK API directly (cross-origin requests, CORS), comma-separated, each exactly as the browser sends it (scheme, host and port if not the default, e.g. `https://client.example.org,http://localhost:3000`); host names in ASCII (punycode for international ones, as browsers send them) without `_`; no wildcards, paths, user info, default ports (`:443`, `:80`), ports with leading zeros, or IPv4 addresses in other forms than four decimal numbers (e.g. `127.1` for `127.0.0.1`), which stop the app at startup. Default: none, and answers carry no CORS headers. See [Cross-origin requests](#cross-origin-requests-cors). Since 2.0.0.
`TESK_API_TRS_URIPATTERN` | Pattern of executor images that TESK API looks up in a [GA4GH Tool Registry Service](#tool-images-from-a-tool-registry) (TRS). Default: `trs://{host}/{id}/versions/{version}`.
`TESK_API_TRS_URLPATTERN` | URL that TESK API asks for such an image. Default: `https://{host}/ga4gh/trs/v2/tools/{id}/versions/{version}`.
`SERVER_FORWARD_HEADERS_STRATEGY` | How TESK API learns its public URL behind a reverse proxy (e.g. for the server URL in the [API description](api.md), which the Swagger UI uses for "Try it out"). `native` (default): the embedded Tomcat takes scheme, host and port from the `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-Port` headers, and the client address from `X-Forwarded-For`, but only of requests from internal addresses (Spring Boot's default: `10.0.0.0/8, 192.168.0.0/16, 169.254.0.0/16, 100.64.0.0/10, fc00::/7, 172.16.0.0/12, ::1/128, 127.0.0.0/8, fe80::/10`); headers from other addresses are ignored. If your proxy has another address, set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to a comma-separated list of CIDR ranges that contains it, and no addresses of untrusted clients. `none`: ignores the headers. Since 2.0.0; before, Spring Boot honoured the headers only in Kubernetes, and up to 1.2.0, Springfox read `X-Forwarded-Host` for the API description itself.

## Pod security and resources

TESK API creates the taskmaster's job, and passes the jobs of the executors
to the taskmaster, which creates them as they are; the taskmaster creates
the filers' jobs with its own settings (`FILER_POD_SECURITY_CONTEXT`,
`FILER_SECURITY_CONTEXT` and `FILER_RESOURCES`, see the [taskmaster's
documentation](https://github.com/elixir-cloud-aai/tesk-core/blob/dev/docs/taskmaster.md#environment-variables);
pass them with `TESK_API_TASKMASTER_ENVIRONMENT_*`).

The security contexts and resources of the taskmaster's and the executors'
pods are JSON objects with the fields of the Kubernetes API, as in a
manifest (camelCase, e.g. `runAsNonRoot`, not `run_as_non_root`).
Quantities are strings (`"500m"`) or numbers. TESK API doesn't start if a
value isn't a JSON object, has a field that the Kubernetes object doesn't
know, or a value of another type (e.g. `"true"` for `true`, `"1000"` for
`1000`, `1.5` for a whole number), if a quantity isn't valid or is
negative, or if a request is above its limit. In security contexts, it also checks
that user and group IDs are between 0 and 2147483647, and that
`seccompProfile.type` is `RuntimeDefault`, `Localhost` (with
`localhostProfile`) or `Unconfined`; other values that Kubernetes refuses
(e.g. unknown resource names) make the creation of the jobs fail instead.

The executors' CPU and memory come from the task: `resources.cpu_cores` and `resources.ram_gb` are both the request and
the limit; without `cpu_cores`, the executors get
`TESK_API_TASKMASTER_EXECUTOR_DEFAULT_CPU`.

### Restricted clusters

Namespaces that enforce the `restricted` [Pod Security
Standard](https://kubernetes.io/docs/concepts/security/pod-security-standards/)
(label `pod-security.kubernetes.io/enforce: restricted`) refuse pods
without these settings: the taskmaster's job is created, but never gets a
pod, and the task stays `QUEUED`. With these values, the taskmaster's and
the executors' pods meet the standard (the filers' pods meet it by default
since taskmaster 0.12.0, the default since 2.1.0 being 0.13.0; if you set
`TESK_API_TASKMASTER_IMAGE_VERSION` and
`TESK_API_TASKMASTER_FILER_IMAGE_VERSION`, keep them at `0.12.0` or later,
as 0.11.0 creates filers without security contexts, which such namespaces
refuse):

```yaml
env:
  - name: TESK_API_TASKMASTER_POD_SECURITY_CONTEXT
    value: '{"runAsNonRoot": true, "seccompProfile": {"type": "RuntimeDefault"}}'
  - name: TESK_API_TASKMASTER_SECURITY_CONTEXT
    value: '{"allowPrivilegeEscalation": false, "capabilities": {"drop": ["ALL"]}}'
  - name: TESK_API_TASKMASTER_EXECUTOR_POD_SECURITY_CONTEXT
    value: '{"runAsNonRoot": true, "runAsUser": 100, "seccompProfile": {"type": "RuntimeDefault"}}'
  - name: TESK_API_TASKMASTER_EXECUTOR_SECURITY_CONTEXT
    value: '{"allowPrivilegeEscalation": false, "capabilities": {"drop": ["ALL"]}}'
```

The taskmaster's image runs as the non-root user 100, so its pod needs no
`runAsUser`. Executors run whatever image a task names, and most tool images
run as root, which `runAsNonRoot` refuses unless `runAsUser` gives another
user: here 100, the user of the filers, so that the executors can read the
inputs and write the outputs that the filers stage on the task's volume.
Images that need root don't run in such a namespace. On clusters that assign
users themselves (e.g. OpenShift with `restricted-v2`), leave `runAsUser`
out. Leave these settings unset on clusters that refuse
`seccompProfile: RuntimeDefault` or set their own security contexts (e.g.
CSC Rahti): there, the pods are as before 2.1.0.

### CPU quotas

Namespaces with a `ResourceQuota` on `limits.cpu` (or `requests.cpu`) refuse
containers without a CPU limit (or request): `must specify limits.cpu`. The
taskmaster's container gets one by default (`TESK_API_TASKMASTER_RESOURCES`),
and so do executors, from the task's `resources.cpu_cores` or from
`TESK_API_TASKMASTER_EXECUTOR_DEFAULT_CPU`. The filers get theirs from the
taskmaster: since taskmaster 0.13.0 (the default since 2.1.0), they request
CPU 100m and memory 128Mi and are limited to CPU 1 and memory 512Mi by
default. The taskmaster's `FILER_RESOURCES` replaces these (taskmaster
0.12.0 or later; 0.11.0 ignores it, and 0.12.0 sets none without it), e.g.
for larger transfers:

```yaml
env:
  - name: TESK_API_TASKMASTER_ENVIRONMENT_FILER_RESOURCES
    value: '{"requests": {"cpu": "100m", "memory": "128Mi"}, "limits": {"cpu": "2", "memory": "1Gi"}}'
```

To turn a default off, set the variable to an empty value (`value: ""`;
for `TESK_API_TASKMASTER_RESOURCES` also `value: '{}'`): an unset variable
keeps the default.

A quota on `limits.memory` also needs memory limits: executors get one only
from `resources.ram_gb`, so give the namespace a `LimitRange` with a default
memory limit for executors of tasks without it.

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
