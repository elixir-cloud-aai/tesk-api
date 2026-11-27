# Access control

TESK API authenticates requests with access tokens of one or more OpenID Connect providers (e.g. [LS Login](https://lifescience-ri.eu/ls-login/)), which it checks itself, against the keys of their issuers. Authentication is on by default. When it is on, every operation on tasks needs a valid access token in the request header, and each task belongs to the caller who created it only: callers get, list and cancel their own tasks, and no others (see [Authorisation](#authorisation)). Groups play no role.

## Switching on/off authentication and authorisation
Authentication and authorisation can be globally switched on and off for TESK API using [Spring Profiles](https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-profiles.html), with `SPRING_PROFILES_ACTIVE` (see [Configuration](configuration.md#settings)):

* `auth` (the default since 2.1.0; before, `noauth` was): all the rules described below apply. TESK API doesn't start without at least one issuer (see [Settings](#settings)).
* `noauth`: all endpoints are unauthenticated (so the setting for `GET /service-info` below has no effect), and every caller is the same user, `anonymousUser`: new tasks are labelled as created by `anonymousUser`, and the API gets, lists and cancels only the tasks created by `anonymousUser`, so tasks created with the `auth` profile are not visible with `noauth` (since 2.1.0; before, all tasks were visible to any user). Every caller may create, get, list and cancel all of these tasks, so TESK API logs a warning at startup with this profile. Use it only for installations that nobody else can reach, e.g. on your own machine, and choose it explicitly.

## Authentication
The TESK API acts as a Resource Server in the OAuth 2.0 contract, whereas the OpenID Connect providers play the role of Authorization Server. All of TESK API endpoints, except the [public ones](#public-endpoints), are secured, which means that in order to reach them, the request has to contain a valid access token of a configured issuer, placed in the `Authorization: Bearer` header.

### Public endpoints
These `GET` and `HEAD` requests need no token, also with the `auth` profile:
* `GET /service-info` (under the base path of the TES API), so that registries and clients can see what a service offers and how to get access. This is the default. To require a valid token for it (any valid token), set the environment variable `TESK_API_SERVICE_INFO_PUBLIC` to `false` (in the [Helm chart of TESK](https://github.com/elixir-cloud-aai/TESK): `auth.serviceInfoPublic`, from chart 0.3.0, which needs TESK API 1.2.0 or later). Service info can name your organisation, contact and storage locations: check the file that `TESK_API_SERVICE_INFO_LOCATION` points to (by default the built-in [`service-info.yaml`](https://github.com/elixir-cloud-aai/tesk-api/blob/dev/src/main/resources/service-info.yaml); see [Configuration](configuration.md#service-info)) if that matters for your deployment.
* The API description, and the Swagger UI if it is on (`TESK_API_SWAGGER_UI_ENABLED`; see [API](api.md#swagger-ui)).

These answer the same with any token, valid or not, as without one: TESK API ignores the `Authorization` header there (since 2.1.0; before, an invalid token gave `HTTP 401 Unauthorized`). With `TESK_API_SERVICE_INFO_PUBLIC` set to `false`, service info is no public endpoint, and its token is checked as for tasks.

### Browser clients (CORS)
Browser apps on other origins can call TESK API only from the origins listed in `TESK_API_CORS_ALLOWED_ORIGINS` (none by default; see [Configuration](configuration.md#cross-origin-requests-cors)). Their preflight requests need no token, also with the `auth` profile, but carry no data and reach no endpoint: every actual request needs a valid token as described here. List only origins that you trust to handle your users' tokens: a listed origin's pages can call TESK API with any token that they hold, and the browser lets them read the answers. CORS doesn't protect TESK API from other clients (e.g. scripts or servers), which send any `Origin` they like or none; it only controls what browsers let web pages read.

### Obtaining a token
TESK API accepts access tokens that a configured issuer issued for TESK: for users, through one of the clients (front ends, e.g. a portal or the Swagger UI below) whose client IDs are listed as the issuer's `audiences`; for services, with the client credentials grant of a listed service client (see [Tokens of services](#tokens-of-services)). The token's `aud` must name one of them (see [Token validation](#token-validation)); how a client gets that audience into its tokens depends on the provider (e.g. LS Login sets `aud` to the client ID of the client that requested the token). Any OAuth 2.0 flow that gives such an access token works.

#### Built-in client
A valid token can be obtained using an OIDC client built in Swagger UI at the TESK API homepage, if the Swagger UI is on (`TESK_API_SWAGGER_UI_ENABLED` set to `true`; it is off by default). In order to use the client, it has to be configured, by placing Client ID - registered at your identity provider with Implicit Grant - in the API environment variable `TESK_API_SWAGGER_OAUTH_CLIENT_ID` (authorisation code grant also possible, with `TESK_API_SWAGGER_OAUTH_IMPLICIT` set to `false`, but discouraged), and by listing that client ID among the issuer's `audiences`, so that its tokens are accepted. The Swagger UI runs in the browser, so it is a public client: register it at your identity provider as a public client, without a client secret. TESK API doesn't pass a client secret to the Swagger UI; `TESK_API_SWAGGER_OAUTH_CLIENT_SECRET` is deprecated and ignored. The Swagger UI of TESK API can't use PKCE yet, so with the authorisation code grant your identity provider must allow it for public clients without PKCE; otherwise use the implicit grant. A token obtained using the Swagger UI of one environment works at any TESK API that lists the same issuer and the same client ID as an audience.

Environment variable | Meaning | Default
--- | --- | ---
`TESK_API_SWAGGER_OAUTH_CLIENT_ID` | Client ID of the Swagger UI at your identity provider. | `changeme`
`TESK_API_SWAGGER_OAUTH_IMPLICIT` | `true`: the Swagger UI gets tokens with the implicit grant, from the authorisation endpoint. `false`: with the authorisation code grant, from the authorisation and token endpoints, without a client secret and without PKCE (see above). | `true`
`TESK_API_SWAGGER_OAUTH_AUTHORIZATION_ENDPOINT` | Authorisation endpoint of the identity provider. | `https://login.elixir-czech.org/oidc/authorize`
`TESK_API_SWAGGER_OAUTH_TOKEN_ENDPOINT` | Token endpoint of the identity provider; used only with the authorisation code grant. | `https://login.elixir-czech.org/oidc/token`
`TESK_API_SWAGGER_OAUTH_SCOPES` | Comma-separated scopes that the Swagger UI offers, each as `<scope>:<description>` (the first `:` separates them). The user picks among them when logging in. | `openid`, `profile` and `email`

For the authorisation code grant, register the Swagger UI as a public client
with the authorisation code grant, and with the redirect URI
`<base URL of TESK API>/swagger-ui/oauth2-redirect.html`; your identity
provider must issue tokens to it without a client secret and without PKCE.

### Token validation
Since 2.1.0, TESK API checks every token itself, without calling the provider (before, it sent user tokens to the provider's user info endpoint). It accepts signed JWT access tokens (as in [RFC 9068](https://datatracker.ietf.org/doc/html/rfc9068)) that pass all of these checks, in this order; it checks no other claims, so claims that RFC 9068 requires but that TESK API doesn't use (e.g. `client_id` in user tokens, or `jti`) may be missing:

1. The request has a bearer token in the `Authorization` header.
2. The token is a single signed JWT (compact form), signed with one of the allowed algorithms (`TESK_API_AUTH_ALGORITHMS`; by default `RS256` and `ES256`; only asymmetric ones can be allowed, never `none` or HMAC).
3. Its `iss` is exactly one of the configured issuers (compared exactly, also a trailing `/`).
4. Its signature is valid with a key of that issuer: from the JWKS URI of the issuer's OpenID Connect discovery document (`<issuer>/.well-known/openid-configuration`, whose `issuer` must be the configured one), or from the issuer's `jwks-uri`. The keys are cached, and fetched again for a token with an unknown key ID, at most once every 10 seconds; once fetched, they are kept for up to 15 minutes past their cache time if fetching them again fails. Requests that arrive while the keys are first fetched wait for them, at most 15 seconds.
5. Its header `typ` is `at+jwt` ([RFC 9068, section 4](https://datatracker.ietf.org/doc/html/rfc9068#section-4)), so that ID tokens aren't accepted as access tokens (unless `TESK_API_AUTH_REQUIRE_ACCESS_TOKEN_TYPE` is `false`).
6. It has `exp`, which isn't past, and `iat`, which isn't in the future nor longer ago than `TESK_API_AUTH_MAX_TOKEN_AGE_SECONDS` (a day by default), and its `nbf`, if it has one, isn't in the future, each with a leeway of `TESK_API_AUTH_CLOCK_SKEW_SECONDS`.
7. Its audience fits: `aud` is or contains one of the issuer's `audiences`; for a listed service client's own token (step 9), `aud` is or contains the client's own ID instead (unless the client has `allow-any-audience`).
8. It has a `sub` that isn't empty.
9. It is classified: a token with an `act` claim (a delegated token, from a token exchange, [RFC 8693](https://datatracker.ietf.org/doc/html/rfc8693)) is refused for now (a later release will accept delegated tokens of trusted actors). A token whose client claim (by default `client_id`) and `sub` are both the ID of one of the issuer's [service clients](#tokens-of-services) is that client's. Any other token is a user's; a user whose `sub` is the ID of a listed service client is refused.
10. The caller is identified by issuer and subject together: its owner ID is `<iss>|<sub>` (see [Task creators](#task-creators)).

TESK API takes the token from the `Authorization` header only (`Bearer <token>`, as in [RFC 6750, section 2.1](https://datatracker.ietf.org/doc/html/rfc6750#section-2.1)), not from an `access_token` parameter in the URL or in a form. A header whose token is empty or has characters that bearer tokens can't have (anything but letters, digits and `-._~+/`, with `=` only at the end; e.g. spaces) is refused without any of the checks above.

A token that fails a check gives `HTTP 401 Unauthorized`, as does a request without a token. TESK API logs why a token was refused (at `INFO`, by the logger `uk.ac.ebi.tsc.tesk.config.security.token.JwtAccessTokenValidator`; e.g. `unknown issuer`, `wrong audience`, `expired`), never the token; the answer names no reason. The answer `401 Unauthorized` has a `WWW-Authenticate` header and a JSON body with the OAuth 2.0 error code and description:

* without a token: `WWW-Authenticate: Bearer realm="oauth", error="unauthorized", error_description="Full authentication is required to access this resource"`;
* with an invalid token: `error="invalid_token"`, with the description `Invalid access token` (`Bearer token is malformed` for a header as above). The token itself is never part of the answer.

```json
{
  "timestamp" : "2026-11-14T10:00:00.000+00:00",
  "status" : 401,
  "error" : "invalid_token",
  "message" : "Invalid access token",
  "path" : "/ga4gh/tes/v1/tasks",
  "error_description" : "Invalid access token"
}
```

If the keys of the token's issuer can't be fetched (at first, or after the cached ones expired; e.g. the issuer is down or answers too slowly), the token can't be checked, and the answer is `HTTP 503 Service Unavailable`, with `Cache-Control: no-store`, without `WWW-Authenticate`, and with the error `temporarily_unavailable` (description `The access token can't be checked now; try again later`), so that clients retry instead of treating their token as a bad credential. After a failed fetch, TESK API answers tokens of that issuer with `503` at once for 10 seconds before it asks the issuer again, so a recovered issuer can take up to 10 seconds to be used.

### Settings
The issuers whose tokens TESK API accepts, and how it checks them, as environment variables (properties `tesk.api.auth.*`; environment variables in upper case, with underscores for dots and list indices and without the dashes, e.g. `TESK_API_AUTH_ISSUERS_0_URL` for `tesk.api.auth.issuers[0].url`). Each issuer `i` (from `0`) and each of its service clients `j` (from `0`) is numbered.

Environment variable | Meaning | Default
--- | --- | ---
`TESK_API_AUTH_ISSUERS_<i>_URL` | Issuer, exactly as in the `iss` claim of its tokens (e.g. `https://login.aai.lifescience-ri.eu/oidc/`). At least one issuer is required with the `auth` profile. | none
`TESK_API_AUTH_ISSUERS_<i>_AUDIENCES_<k>` | Audiences of users' tokens: the client IDs of the front ends through which users get tokens for TESK (e.g. a portal, the Swagger UI). A user's token is accepted if its `aud` is or contains one of them. As one comma-separated list: `TESK_API_AUTH_ISSUERS_<i>_AUDIENCES`. Required unless the issuer lists only service clients (then its users' tokens are all refused). | none
`TESK_API_AUTH_ISSUERS_<i>_SERVICECLIENTS_<j>_CLIENTID` | ID of a service client of that issuer whose own tokens are accepted (see [Tokens of services](#tokens-of-services)). | none
`TESK_API_AUTH_ISSUERS_<i>_SERVICECLIENTS_<j>_ALLOWANYAUDIENCE` | `true`: that client's own tokens are accepted whatever their `aud`, or without one. Opt-in, for testing or while setting up a federation; see [Trust implications](#trust-implications). | `false`
`TESK_API_AUTH_ISSUERS_<i>_JWKSURI` | JWKS URI of the issuer, if it shouldn't come from the discovery document. | from discovery
`TESK_API_AUTH_ISSUERS_<i>_CLIENTCLAIM` | Claim that names the client of a token. Some providers use `azp`. | `client_id`
`TESK_API_AUTH_TOKEN_VALIDATOR` | How tokens are checked. Only `jwt` (JWT access tokens, as above) exists. | `jwt`
`TESK_API_AUTH_ALGORITHMS` | Allowed signature algorithms, comma-separated; asymmetric ones only: `RS256`, `RS384`, `RS512`, `PS256`, `PS384`, `PS512`, `ES256`, `ES384` and `ES512`. | `RS256,ES256`
`TESK_API_AUTH_REQUIRE_ACCESS_TOKEN_TYPE` | `true`: the header `typ` must be `at+jwt`. Set it to `false` only for providers that don't set it. | `true`
`TESK_API_AUTH_CLOCK_SKEW_SECONDS` | Leeway for `exp`, `nbf` and `iat` (in the future), in seconds. | `60`
`TESK_API_AUTH_MAX_TOKEN_AGE_SECONDS` | Maximum age of a token (since `iat`), in seconds. | `86400`
`TESK_API_AUTH_ISSUERS_<i>_USERINFOURI` | User info endpoint of the issuer, if it shouldn't come from the discovery document; used only to look up claims for the [decision service](#decision-service). `https://` only (plain `http://` only to the local host). | from discovery
`TESK_API_AUTH_TIMEOUT_SECONDS` | Connect and read timeouts of the requests to the issuers (discovery document, keys, user info), in seconds, from 1 to 60. | `10`

For example, as properties (e.g. in `application.properties`):

```properties
tesk.api.auth.issuers[0].url=https://login.aai.lifescience-ri.eu/oidc/
tesk.api.auth.issuers[0].audiences=<portal client ID>,<Swagger UI client ID>
tesk.api.auth.issuers[0].service-clients[0].client-id=<gateway client ID>
```

or as environment variables:

```bash
TESK_API_AUTH_ISSUERS_0_URL=https://login.aai.lifescience-ri.eu/oidc/
TESK_API_AUTH_ISSUERS_0_AUDIENCES=<portal client ID>,<Swagger UI client ID>
TESK_API_AUTH_ISSUERS_0_SERVICECLIENTS_0_CLIENTID=<gateway client ID>
```

With the `auth` profile, TESK API doesn't start, and says why, with invalid settings: no issuer; an issuer with neither audiences nor service clients, or listed twice; an issuer, JWKS URI or user info URI that isn't `https://` (plain `http://` only to the local host, e.g. for tests), or an issuer with a query or fragment; a blank audience; a blank or duplicate client ID; a symmetric algorithm or `none`; a negative leeway, a maximum age that isn't positive, or a timeout outside 1 to 60 seconds; an unknown `TESK_API_AUTH_TOKEN_VALIDATOR`; service clients listed under no issuer (`tesk.api.auth.service-clients`, i.e. `TESK_API_AUTH_SERVICE_CLIENTS_*` or `TESK_API_AUTH_SERVICECLIENTS_*`). In any profile, it doesn't start with a setting under `tesk.api.authorisation` (`TESK_API_AUTHORISATION_*`, removed; see [Upgrading](upgrading.md#to-210)). The discovery documents and the keys are fetched at the first token of each issuer, not at startup.

For example, [LS Login](https://lifescience-ri.eu/ls-login/) (issuer
`https://login.aai.lifescience-ri.eu/oidc/`) issues access tokens with the
header `typ` `at+jwt`, signed (`RS256`) with a key of the issuer's JWKS, and
with the claims `iss`, `client_id`, `sub`, `exp`, `iat`, `jti`, `scope` and
`aud`. The defaults fit these tokens: set the issuer, the client IDs of your
front ends as audiences, and your service clients.

TESK API reads no claims of a token but those named under [Token
validation](#token-validation), and those that it passes on to the
[decision service](#decision-service). It no longer validates tokens at the
user info endpoint of the provider, and `SECURITY_OAUTH2_RESOURCE_USER_INFO_URI`
is ignored; it asks the user info endpoint only for claims for the decision
service, when creating a task.

### Decision service

With the `auth` profile, a decision service decides who may create tasks:
an [OpenID AuthZEN 1.0](https://openid.net/specs/authorization-api-1_0.html)
Access Evaluation endpoint, e.g. [tes-authz](https://github.com/elixir-cloud-aai/tes-authz),
which implements the profile `tes-authzen/3` that TESK API speaks. TESK API
asks it once for every task that a caller creates, after the token has
passed [Token validation](#token-validation), and does what it answers:

Answer | Result
--- | ---
`{"decision": true}` | The task is created.
`{"decision": false}` | `403 Forbidden`, without a call to Kubernetes.
No decision: no answer within the timeout, a status other than `200`, or a body without a boolean `decision` | `503 Service Unavailable` (`Authorisation unavailable; try again later`), without a call to Kubernetes. Creating tasks never fails open; the cause is logged.

The request (`POST`, JSON, with a new `X-Request-ID` each) describes:

* the subject: type `user`, id the caller's owner ID (`<iss>|<sub>`, see
  [Task creators](#task-creators)), with the properties `iss`, `sub`,
  `client_id` and `scope` (as a list) of the token, if present, and
  `claims`: the claims listed in `TESK_API_AUTHZ_DECISIONSERVICE_CLAIMS`
  (see below) that the caller has;
* the action `CreateTask`;
* the resource: type `tes_task_set`, id `own`, with the property `owner`
  (the owner ID that the task gets) and `task`, a fixed projection of the
  submitted task: `name`, `tags`, `resources` (`cpu_cores`, `ram_gb`,
  `disk_gb`, `preemptible`, `zones`), `volumes`, the `image` of each
  executor, and `url`, `path` and `type` of each input and output, with
  user info, query and fragment removed from URLs. Commands, environments,
  input contents and backend parameters are never sent, as they may hold
  secrets;
* the context: `tes.profile` `tes-authzen/3` and `tes.service_id`, the ID
  of the service info.

The caller's access token is never sent to the decision service. Getting,
listing and cancelling tasks don't ask it: they follow TESK API's own rules
(see [Authorisation](#authorisation)).

Claims named in `TESK_API_AUTHZ_DECISIONSERVICE_CLAIMS` (none by default)
are looked up in this order, and the first one found is sent: a claim of
that exact name in the token, then at the user info endpoint of the
token's issuer, then, for names with dots (e.g. `a.b`), the nested claim
`b` of the object `a` in the token, then at the user info endpoint. TESK
API calls the user info endpoint (with the caller's token, at most once
per create, and never for other operations) only if a listed claim isn't
in the token by its exact name, and never for tokens of clients (listed
service clients, and tokens whose `sub` is their client ID), which have no
user info. The endpoint is `TESK_API_AUTH_ISSUERS_<i>_USERINFOURI`, or the
`userinfo_endpoint` of the issuer's discovery document; it must be
`https://` (plain `http://` only to the local host), and its answer must
be a JSON object with the token's `sub`. If it refuses the token (`401` or
`403`) or names another `sub`, the answer is `401 Unauthorized`, with
`WWW-Authenticate` and the error `invalid_token`, as for other invalid
tokens; if it doesn't answer within `TESK_API_AUTH_TIMEOUT_SECONDS`,
answers with another status, or with something other than a JSON object
(e.g. a signed JWT), it is `503 Service Unavailable`.

Environment variable | Meaning | Default
--- | --- | ---
`TESK_API_AUTHZ_DECISIONSERVICE_URL` | URL of the Access Evaluation endpoint, e.g. `https://tes-authz.example.org/access/v1/evaluation`. Required with the `auth` profile; `https://` only (plain `http://` only to the local host, e.g. a sidecar). | none
`TESK_API_AUTHZ_DECISIONSERVICE_TIMEOUT` | Timeout of a request to the decision service as a whole, in seconds, from 1 to 60. | `2`
`TESK_API_AUTHZ_DECISIONSERVICE_CLAIMS` | Claims of the caller that the decision service gets, comma-separated (or one per `TESK_API_AUTHZ_DECISIONSERVICE_CLAIMS_<k>`; not as a JSON array). | none
`TESK_API_AUTHZ_DECISIONSERVICE_CREDENTIALFILE` | File with a bearer credential for the decision service, sent as `Authorization: Bearer <credential>` (one line; read for every request, so it can be rotated in place, e.g. as a mounted Kubernetes secret). | none (no `Authorization` header)

With the `auth` profile, TESK API doesn't start, and says why, without
`TESK_API_AUTHZ_DECISIONSERVICE_URL`, with a URL that isn't `https://`
(except to the local host), a timeout outside 1 to 60 seconds, a blank
claim name, or a credential file that can't be read, is empty or has more
than one line. With the `noauth` profile, it doesn't start if any setting
under `tesk.api.authz` (`TESK_API_AUTHZ_*`) is set: the decision service
needs authentication.

For example, for [LS Login](https://lifescience-ri.eu/ls-login/), whose
tokens carry no entitlements but whose user info does, with tes-authz next
to TESK API:

```bash
TESK_API_AUTHZ_DECISIONSERVICE_URL=https://tes-authz.example.org/access/v1/evaluation
TESK_API_AUTHZ_DECISIONSERVICE_CLAIMS=eduperson_entitlement
```

The policy of tes-authz then decides, e.g., that members of a group named in
`eduperson_entitlement` may create tasks. Your front ends need the scope
that releases the claim at the user info endpoint (for LS Login,
`eduperson_entitlement`) when they get tokens for users.

## Authorisation

Since 2.1.0, TESK API has no group model: no groups, teams, team admins or
super admins, and no rights of its own from claims of the token or the user
info ([#225](https://github.com/elixir-cloud-aai/tesk-api/issues/225)); such
claims count only where the decision service uses them. With the `auth`
profile:

Operation | Who may | Otherwise
--- | --- | ---
Create a task | Whoever the [decision service](#decision-service) (AuthZEN, e.g. [tes-authz](https://github.com/elixir-cloud-aai/tes-authz)) allows. | `403 Forbidden` if it denies, `503 Service Unavailable` if it gives no decision; both without a call to Kubernetes
Get a task | Its creator only (see [Task creators](#task-creators)), in every view. | `404 Not Found`, as for a task that doesn't exist
Cancel a task | Its creator only. | `404 Not Found`, as for a task that doesn't exist; nothing is changed
List tasks | Everyone; the list holds the caller's own tasks only. | (an empty list)

This holds for every caller, users and [service clients](#tokens-of-services)
alike; for getting, listing and cancelling, whatever groups or entitlements
its token names. Another user's
task is not found (`404`), not forbidden: the answer, and the requests that
TESK API makes to Kubernetes for it, are the same as for a task that doesn't
exist, so that callers can't tell whether another user's task exists.

Tasks can reference or contain private data (inputs, outputs, logs,
environment), and access to data is granted to individual people, so nobody
but a task's creator sees it through the API: admins can't read other users'
tasks or cancel them. Operators handle stuck or abusive tasks through
Kubernetes (e.g. with `kubectl`), not through the TES API. Clients that share
tasks between users do so themselves.

The settings of the group model and of the former service clients (all
settings under `tesk.api.authorisation`, i.e. environment variables
`TESK_API_AUTHORISATION_*`) are removed: if one of them is set, TESK API
doesn't start, and names it, with what replaces it, if anything (see
[Upgrading](upgrading.md#to-210)). The tag
`GROUP_NAME` is a tag like any other now, and `logs[0].metadata` has no
`GROUP_NAME`.

### Task creators

TESK API identifies each caller by the issuer and the subject of its token
together, as the owner ID `<iss>|<sub>` (e.g.
`https://login.aai.lifescience-ri.eu/oidc/|1234abcd@lifescience-ri.eu`), for
users and [service clients](#tokens-of-services) alike, compared exactly, so
that the same `sub` at two issuers is two callers. It records the creator of a
task on the task's Kubernetes objects (the taskmaster's job, its ConfigMap and
the executors' jobs):

* the label `creator-user-hash`: the first 32 hexadecimal digits (128 bits)
  of the SHA-256 hash of the owner ID (in UTF-8). Kubernetes selects a
  caller's tasks by it. Label values are limited to 63 characters from
  `[A-Za-z0-9._-]`; the hash fits any owner ID.
* the annotation `creator-user-id`: the owner ID itself. A task is the
  caller's only if the annotation is the caller's owner ID and the label is
  its hash.

`logs[0].metadata.USER_ID` of a task (in the `BASIC` and `FULL` views) shows
the annotation.

Tasks created before 2.1.0 record the bare `sub` as their creator (TESK API
1.2.0 to 2.0.0), or only the label `creator-user-id`, with the `sub`'s `@`
replaced by `_` (TESK API 1.1.0 or earlier). They belong to nobody: no caller
gets, lists or cancels them through the API (`404 Not Found`), and their
`USER_ID` is the bare `sub`, or empty. Delete them through Kubernetes (see
[Upgrading](upgrading.md#to-210)). TESK API no longer writes the labels and
annotations `creator-group-name`; those on older tasks are ignored.

## Tokens of services

Gateways and other services call TESK API with access tokens of their own,
obtained with the OAuth 2.0 client credentials grant ([RFC 6749, section
4.4](https://datatracker.ietf.org/doc/html/rfc6749#section-4.4)). Such
tokens have no user. TESK API accepts the tokens of the service clients
listed under an issuer (`TESK_API_AUTH_ISSUERS_<i>_SERVICECLIENTS_<j>_CLIENTID`,
see [Settings](#settings)); none are listed by default.

### Which tokens are accepted

A token is a listed service client's own if it is of that client's issuer,
and both its client claim (by default `client_id`, as in [RFC
9068](https://datatracker.ietf.org/doc/html/rfc9068)) and its `sub` are the
client ID, as in tokens of the client credentials grant. It goes through all
the checks of [Token validation](#token-validation), with one difference:
its `aud` must be or contain the client's **own** ID (not one of the
issuer's `audiences`), so a token that the client got for another service,
with that service's audience, is refused, and so is a token of one listed
client for another. Register each service client at your provider so that
its tokens for TESK name its own client ID as audience.

`allow-any-audience` (`TESK_API_AUTH_ISSUERS_<i>_SERVICECLIENTS_<j>_ALLOWANYAUDIENCE`,
default `false`) accepts that client's own tokens whatever their `aud`, or
without one. It is opt-in and meant for testing, and for setting up a
federation while the audiences of the clients at the identity provider are
still being sorted out (e.g. if you don't run the provider and can't change a
client's registration yourself). Its risk: every token that the trusted
issuer issues to that client ID is accepted, also tokens meant for other
services, so a token that leaks from another service, or that another
service replays, works at TESK API too (see [Trust
implications](#trust-implications)). In production, leave it off. It applies
to that client's own tokens only, not to users' tokens obtained through it,
and not to other listed clients. TESK API logs a warning at startup that
names each client with `allow-any-audience`.

All other tokens are users' tokens, also those that users obtained through a
listed client (their `sub` is the user's): they need one of the issuer's
`audiences`. A user whose `sub` is a listed client's ID is refused (`401
Unauthorized`), so that no user can act as a client. A service client that
isn't listed is a user like any other if its token has one of the issuer's
`audiences`, and is refused otherwise.

### Authorisation of service clients

A listed client is a principal of its own: its owner ID is `<iss>|<client
ID>`. The rules of [Authorisation](#authorisation) apply to it as to users:
it may list, get and cancel the tasks that it created, and no others, also
none of other listed clients; whether it may create tasks is up to the
[decision service](#decision-service), which gets its client ID (its
token is never sent to the user info endpoint). Its tasks
record it as their creator as for users (see [Task
creators](#task-creators)).

A gateway that runs tasks for its users runs all of them as its client:
TESK API doesn't know the users behind it, and the gateway decides which of
its users may see and cancel which task.

### Trust implications

* Everyone who holds a listed client's credentials (e.g. its client secret)
  can get tokens and use TESK API as that client: if the decision service
  lets it create tasks, run any container image with any command on your
  cluster, within the resources that tasks may request and the limits of
  the decision service's policy; and see and cancel all tasks of that
  client. Keep the credentials as safe as an administrator's, list only
  clients of services that you trust, and remove a client from the list (and
  rotate its credentials) if they may have been disclosed.
* All tokens are checked locally, so a token stays valid until it expires
  (`exp`), also if the user or client is disabled at the provider in the
  meantime. Removing a client from the list (with a restart of TESK API)
  takes effect at once.
* With `allow-any-audience`, TESK API accepts every token that the issuer
  issues to that client ID, whatever its `aud`, also tokens that the client
  got for other services: whoever receives such a token (e.g. that other
  service), or obtains one that leaked from it, can use it at TESK API until
  it expires. Use it only for testing or while setting up a federation.
* TESK API trusts every key that it gets from the issuer's discovery
  document or JWKS URI, so these must be `https://` URLs (plain `http://`
  only to the local host, e.g. for tests), so that nobody on the network
  path can give it keys of their own: with other URLs in the settings, TESK
  API doesn't start, and a discovery document of another `issuer`, or with
  a `jwks_uri` that isn't `https://`, is refused (tokens of that issuer then
  get `503 Service Unavailable`).
