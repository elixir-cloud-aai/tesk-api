# Access control

TESK API integrates with an OpenID Connect provider (e.g. [LS Login](https://lifescience-ri.eu/ls-login/) or [Elixir AAI](https://www.elixir-europe.org/services/compute/aai)) via the [OpenID Connect](http://openid.net/connect/) protocol to authenticate requests. Authentication is optional and can be switched off completely. When it is on, every operation on tasks needs a valid access token of the provider in the request header, and each task belongs to the user who created it only: users get, list and cancel their own tasks, and no others (see [Authorisation](#authorisation)). Groups play no role.

## Switching on/off authentication and authorisation
Authentication and authorisation can be globally switched on and off for TESK API using [Spring Profiles](https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-profiles.html). When the default `noauth` profile is active, all endpoints are unauthenticated (so the setting for `GET /service-info` below has no effect), and every caller is the same user, `anonymousUser`: new tasks are labelled as created by `anonymousUser`, and the API gets, lists and cancels only the tasks created by `anonymousUser`, so tasks created with the `auth` profile are not visible with `noauth` (since 2.1.0; before, all tasks were visible to any user). Creating tasks works as before. With `auth` profile activated, all the rules described below apply. Set the profile with `SPRING_PROFILES_ACTIVE` (see [Configuration](configuration.md#settings)).

## Authentication
The TESK API acts as a Resource Server in the OIDC contract, whereas Elixir AAI plays the role of Authorization Server. All of TESK API endpoints, except the [public ones](#public-endpoints), are secured, which means that in order to reach them, the request has to contain a valid _access_token_ issued by Elixir AAI, placed in `authorization: Bearer` header. 

### Public endpoints
These `GET` and `HEAD` requests need no token, also with the `auth` profile:
* `GET /service-info` (under the base path of the TES API), so that registries and clients can see what a service offers and how to get access. This is the default. To require a valid token for it (no group membership needed), set the environment variable `TESK_API_SERVICE_INFO_PUBLIC` to `false` (in the [Helm chart of TESK](https://github.com/elixir-cloud-aai/TESK): `auth.serviceInfoPublic`, from chart 0.3.0, which needs TESK API 1.2.0 or later). Service info can name your organisation, contact and storage locations: check the file that `TESK_API_SERVICE_INFO_LOCATION` points to (by default the built-in [`service-info.yaml`](https://github.com/elixir-cloud-aai/tesk-api/blob/dev/src/main/resources/service-info.yaml); see [Configuration](configuration.md#service-info)) if that matters for your deployment.
* The API description, and the Swagger UI if it is on (`TESK_API_SWAGGER_UI_ENABLED`; see [API](api.md#swagger-ui)).

A request that does carry a token has it validated on these too: with an invalid token, the answer is `HTTP 401 Unauthorized`.

### Browser clients (CORS)
Browser apps on other origins can call TESK API only from the origins listed in `TESK_API_CORS_ALLOWED_ORIGINS` (none by default; see [Configuration](configuration.md#cross-origin-requests-cors)). Their preflight requests need no token, also with the `auth` profile, but carry no data and reach no endpoint: every actual request needs a valid token as described here. List only origins that you trust to handle your users' tokens: a listed origin's pages can call TESK API with any token that they hold, and the browser lets them read the answers. CORS doesn't protect TESK API from other clients (e.g. scripts or servers), which send any `Origin` they like or none; it only controls what browsers let web pages read.

### Obtaining a token
At the moment (subject to change) TESK API does not require the access token to be obtained using any specific client (application). Also the token audience is not currently checked (it is not clear, if Elixir AAI supports defining a token audience). Therefore, a valid token has the following properties:
* Access token
* Not expired
* Obtained by a user using any registered Elixir AAI client
* Obtained by a user using any OIDC Flow
* Containing any audience

#### Built-in client
A valid token can be obtained using an OIDC client built in Swagger UI at the TESK API homepage, if the Swagger UI is on (`TESK_API_SWAGGER_UI_ENABLED` set to `true`; it is off by default). In order to use the client, it has to be configured, by placing Client ID - registered at Elixir AAI with Implicit Grant - in the API environment variable `TESK_API_SWAGGER_OAUTH_CLIENT_ID` (authorisation code grant also possible, with `TESK_API_SWAGGER_OAUTH_IMPLICIT` set to `false`, but discouraged). The Swagger UI runs in the browser, so it is a public client: register it at your identity provider as a public client, without a client secret. TESK API doesn't pass a client secret to the Swagger UI; `TESK_API_SWAGGER_OAUTH_CLIENT_SECRET` is deprecated and ignored. The Swagger UI of TESK API can't use PKCE yet, so with the authorisation code grant your identity provider must allow it for public clients without PKCE; otherwise use the implicit grant. Unless groups-clients relationship is introduced, token obtained using Swagger UI from one environment can be used to make calls to API installed elsewhere.

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
TESK API validates the token by hitting Elixir AAI `user_info` endpoint on each request and extracting information returned from that endpoint, using the resource server of Spring Security. A token is valid if the endpoint answers it (`200 OK`) with a JSON object that has a `sub` (not blank) and no `error`. TESK API waits at most 10 seconds to connect to the endpoint and 30 seconds for each read of its answer (these limits don't cover the name lookup of its host, nor the answer as a whole); if the endpoint can't be reached or doesn't answer in time, the token is refused like an invalid one. An invalid token, or lack of thereof in the `Authorization: Bearer` header, will result in `HTTP 401 Unauthorized` error. Tokens of allow-listed services are checked locally instead, if that is on (see [Tokens of services](#tokens-of-services)).

TESK API takes the token from the `Authorization` header only (`Bearer <token>`, as in [RFC 6750, section 2.1](https://datatracker.ietf.org/doc/html/rfc6750#section-2.1)), not from an `access_token` parameter in the URL or in a form. A header whose token is empty or has characters that bearer tokens can't have (anything but letters, digits and `-._~+/`, with `=` only at the end; e.g. spaces) is refused without a call to the user info endpoint.

The answer `401 Unauthorized` has a `WWW-Authenticate` header and a JSON body with the OAuth 2.0 error code and description:

* without a token: `WWW-Authenticate: Bearer realm="oauth", error="unauthorized", error_description="Full authentication is required to access this resource"`;
* with an invalid token: `error="invalid_token"`, with the description `Invalid access token` (`Bearer token is malformed` for a header as above; `Invalid access token of a service client` or `User ID of a service client` for [tokens of services](#tokens-of-services)). The token itself is never part of the answer.

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

### Integrating with a different OIDC provider
We have not tested TESK API integration with a different OIDC provider than Elixir AAI, but since tokens are validated at the standard user info endpoint of OpenID Connect, it should be possible (just point `security.oauth2.resource.user-info-uri`, or `SECURITY_OAUTH2_RESOURCE_USER_INFO_URI`, to a proper URL), provided that its user info has a `sub`, which identifies the user; TESK API reads nothing else from it.

## Authorisation

Since 2.1.0, TESK API has no group model: no groups, teams, team admins or
super admins, and no rights from claims of the user info
([#225](https://github.com/elixir-cloud-aai/tesk-api/issues/225)). With the
`auth` profile:

Operation | Who may | Otherwise
--- | --- | ---
Create a task | Nobody, for now: the right to create tasks is to be decided by a decision service (AuthZEN, e.g. [tes-authz](https://github.com/elixir-cloud-aai/tes-authz)), which TESK API doesn't ask yet. | `403 Forbidden`, without a call to Kubernetes
Get a task | Its creator only (see [Task creators](#task-creators)), in every view. | `404 Not Found`, as for a task that doesn't exist
Cancel a task | Its creator only. | `404 Not Found`, as for a task that doesn't exist; nothing is changed
List tasks | Everyone; the list holds the caller's own tasks only. | (an empty list)

This holds for every caller, users and [service clients](#tokens-of-services)
alike, whatever groups or entitlements the user info names. Another user's
task is not found (`404`), not forbidden: the answer, and the requests that
TESK API makes to Kubernetes for it, are the same as for a task that doesn't
exist, so that callers can't tell whether another user's task exists.

Tasks can reference or contain private data (inputs, outputs, logs,
environment), and access to data is granted to individual people, so nobody
but a task's creator sees it through the API: admins can't read other users'
tasks or cancel them. Operators handle stuck or abusive tasks through
Kubernetes (e.g. with `kubectl`), not through the TES API. Clients that share
tasks between users do so themselves.

The settings of the group model (all settings under `tesk.api.authorisation`,
i.e. environment variables `TESK_API_AUTHORISATION_*`, except those of the
[service clients](#settings)) are removed: if one of them is set, TESK API
doesn't start, and names it (see [Upgrading](upgrading.md#to-210)). The tag
`GROUP_NAME` is a tag like any other now, and `logs[0].metadata` has no
`GROUP_NAME`.

### Task creators

TESK API identifies users by the `sub` claim of the user info, compared
exactly. It records the creator of a task on the task's Kubernetes objects (the
taskmaster's job, its ConfigMap and the executors' jobs):

* the label `creator-user-hash`: the first 32 hexadecimal digits (128 bits)
  of the SHA-256 hash of the `sub` (in UTF-8). Kubernetes selects a user's
  tasks by it. Label values are limited to 63 characters from
  `[A-Za-z0-9._-]`; the hash fits any `sub`.
* the annotation `creator-user-id`: the `sub` itself. A task is the user's
  only if the annotation is the user's `sub` and the label is its hash.

`logs[0].metadata.USER_ID` of a task (in the `BASIC` and `FULL` views) shows
the annotation.

Tasks without both (those created by TESK API 1.1.0 or earlier, which have
only the label `creator-user-id`, with the `sub`'s `@` replaced by `_`)
belong to nobody: no caller gets, lists or cancels them through the API, and
their `USER_ID` is empty. TESK API no longer writes the labels and
annotations `creator-group-name`; those on older tasks are ignored.

## Tokens of services

Gateways and other services call TESK API with access tokens of their own,
obtained with the OAuth 2.0 client credentials grant ([RFC 6749, section
4.4](https://datatracker.ietf.org/doc/html/rfc6749#section-4.4)). Such
tokens have no user: the user info endpoint may refuse them, and releases
no groups if it answers. TESK API can accept the tokens of a list of such
clients instead, and check them itself, without the user info endpoint. The
setting is off by default; with it off, nothing changes. It needs the `auth`
profile.

### Which tokens are accepted

With the setting on, TESK API looks at each bearer token first. If it is a
signed JWT whose `iss` is the configured issuer (compared exactly, also a
trailing `/`), whose client claim (by default `client_id`, as in [RFC
9068](https://datatracker.ietf.org/doc/html/rfc9068)) is one of the listed
client IDs, and whose `sub` is that same client ID (as in tokens of the
client credentials grant; see `…_REQUIRE_CLIENT_SUBJECT` below), it is a
service token, and TESK API checks it locally:

* the signature, against the issuer's JSON Web Key Set: from the JWKS URI
  in the issuer's OpenID Connect discovery document
  (`<issuer>/.well-known/openid-configuration`, whose `issuer` must be the
  configured issuer), or from `…_JWKS_URI`. Only asymmetric algorithms (RSA,
  RSA-PSS, ECDSA) are accepted. The keys are cached, and fetched again for a
  token with an unknown key ID, at most once every 10 seconds;
* `iat`, which the token must have ([RFC 9068, section
  2.2](https://datatracker.ietf.org/doc/html/rfc9068#section-2.2)), and which
  may be neither in the future nor more than a year old;
* `exp`, which the token must have, and `nbf`, if it has one, with
  a leeway of `…_CLOCK_SKEW_SECONDS`;
* the header `typ`, which must be `at+jwt` ([RFC 9068, section
  4](https://datatracker.ietf.org/doc/html/rfc9068#section-4)), so that ID
  tokens aren't accepted as access tokens (unless
  `…_REQUIRE_ACCESS_TOKEN_TYPE` is `false`);
* the audience: `aud` must be or contain one of the audiences listed for
  **that** client in `…_CLIENTS` (unless the client has `allowAnyAudience`),
  so a client's token for another listed client's audience is refused.

If all of these hold, the request is authenticated as that client, without
a call to the user info endpoint. If one fails, the request is refused with
`401 Unauthorized`, also without a call to the user info endpoint.

All other tokens go the existing way, through the user info endpoint, so
user tokens keep working as before: tokens that aren't JWTs, JWTs of other
issuers or of clients that aren't listed, and tokens of users obtained
through a listed client (their `sub` is the user's). A client that isn't
listed is refused by the user info endpoint (`401 Unauthorized`) or, if the
endpoint answers, is a user like any other (its own tasks only).

TESK API logs the client ID (never the token) for each accepted (`INFO`) and
refused (`WARN`) service token, with the reason of a refusal (e.g.
`expired`, `invalid signature`, `wrong audience`). A service that polls
often makes many such lines; to log refusals only, set the logger
`uk.ac.ebi.tsc.tesk.config.security.ServiceClientTokenServices` to `WARN`
(e.g. with the environment variable
`LOGGING_LEVEL_UK_AC_EBI_TSC_TESK_CONFIG_SECURITY_SERVICECLIENTTOKENSERVICES=WARN`).

### Settings

Environment variable | Meaning | Default
--- | --- | ---
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_ENABLED` | `true`: tokens of the clients below are accepted as described above. | `false`
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_ISSUER` | Issuer of the tokens, exactly as in their `iss` claim (e.g. `https://login.example.org/oidc/`). Required if the setting is on. | none
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_CLIENTS` | The clients whose tokens are accepted, with the audiences of each, as a JSON object from client ID (compared exactly) to the client's settings (see below). Required if the setting is on. Since 2.1.0; replaces `…_CLIENT_IDS` and `…_AUDIENCE`. | none
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_JWKS_URI` | JWKS URI of the issuer, if it shouldn't come from the discovery document. | from discovery
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_CLIENT_CLAIM` | Claim that names the client. Some providers use `azp`. | `client_id`
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_REQUIRE_ACCESS_TOKEN_TYPE` | `true`: the header `typ` must be `at+jwt`. Set it to `false` only for providers that don't set it. | `true`
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_REQUIRE_CLIENT_SUBJECT` | `true`: the token's `sub` must be the client ID, as in tokens of the client credentials grant; tokens of users obtained through a listed client go through the user info endpoint, as user tokens. `false`: any `sub`; then every token that a user gets through a listed client is accepted as the client's. | `true`
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_CLOCK_SKEW_SECONDS` | Leeway for `exp`, `nbf` and `iat` (in the future), in seconds. | `60`

Each client's settings are an object with these fields:

* `audiences`: a non-empty list of audiences (non-blank strings); the
  client's tokens are accepted if their `aud` is or contains one of them.
  Recommended: register each client so that its tokens for TESK get an
  audience of their own (e.g. the URL of your TESK API), and list it here,
  so that tokens that a client got for other services aren't accepted.
* `allowAnyAudience`: `true` accepts the client's tokens whatever their
  `aud`, or without one, as before 2.1.0 without `…_AUDIENCE`, and can't be
  combined with `audiences`; `false`, the default, needs `audiences`. It is
  opt-in and meant for testing, and for setting up a federation while the
  audiences of the clients at the identity provider are still being sorted
  out (e.g. if you don't run the provider and can't change a client's
  registration yourself). Its risk: every token that the trusted issuer
  issues to that client ID is accepted, also tokens meant for other
  services, so a token that leaks from another service, or that another
  service replays, works at TESK API too (see [Trust
  implications](#trust-implications)). In production, list the client's
  `audiences` instead. TESK API logs a warning at startup that names each
  client with `allowAnyAudience`.

For example, a gateway's client accepted at TESK's own audience, and a
client for smoke tests at two:

```bash
TESK_API_AUTHORISATION_SERVICE_CLIENTS_CLIENTS='{
  "gateway-client-id": {"audiences": ["https://tesk.example.org"]},
  "smoke-test-client-id": {"audiences": ["https://tesk.example.org", "tesk-smoke"]}
}'
```

A token of `gateway-client-id` with `aud` `tesk-smoke` is refused. In
`application.properties`, the property
`tesk.api.authorisation.service-clients.clients` takes the same JSON, on one
line. Client IDs and audiences are trimmed.

If the setting is on without an issuer, with a negative leeway, or with
`…_CLIENTS` unset or invalid (not a JSON object, no client, a blank or
duplicate client ID, a client without `audiences` or with an empty list or
a blank audience, both `audiences` and `allowAnyAudience`, or another
field), TESK API doesn't start, and says why. With the setting on, it
doesn't start either if the removed settings `…_CLIENT_IDS` or `…_AUDIENCE`
of 2.0.0 are still set (see [Upgrading](upgrading.md#to-210)); with it off,
they have no effect. The discovery document and
the keys are fetched at the first service token, not at startup, with
timeouts of 5 seconds. Until that succeeds, service tokens are refused; after
a failure (also a key set without keys), TESK API refuses them at once for 10
seconds before it asks the issuer again, so a recovered issuer can take up to
10 seconds to be used. Once fetched, the keys are kept for up to 15 minutes
past their cache time if fetching them again fails.

For example, [LS Login](https://lifescience-ri.eu/ls-login/) (issuer
`https://login.aai.lifescience-ri.eu/oidc/`) issues access tokens of the
client credentials grant with the header `typ` `at+jwt`, signed (`RS256`)
with a key of the issuer's JWKS, and with the claims `iss`, `client_id` and
`sub` (both the client ID), `exp` (an hour after `iat`), `iat`, `jti`,
`scope` and `aud`, which is set per client when the client is registered.
The defaults fit these tokens: set the issuer, and the clients with the
audiences that you registered for TESK.

### Authorisation of service clients

A listed client is a principal of its own: its ID is the client ID. The
rules of [Authorisation](#authorisation) apply to it as to users: it
may list, get and cancel the tasks that it created, and no others, also none
of other listed clients; creating tasks is denied for now.

Tasks of a client record it as their creator as for users (see [Task
creators](#task-creators)): the label `creator-user-hash` has the hash of the
client ID, the annotation `creator-user-id` and `logs[0].metadata.USER_ID`
the client ID.

User IDs (`sub` of the user info) and client IDs share this record, so a
user whose `sub` is a listed client's ID would share the client's tasks:
with the setting on, TESK API refuses such users (`401 Unauthorized`, logged
as a warning). Providers give clients and users IDs of different forms, so
this happens only if the user info endpoint is of another provider, whose
users could have such IDs.

A gateway that runs tasks for its users runs all of them as its client:
TESK API doesn't know the users behind it, and the gateway decides which of
its users may see and cancel which task.

### Trust implications

* Everyone who holds a listed client's credentials (e.g. its client secret)
  can get tokens and run any container image with any command on your
  cluster, within the resources that tasks may request, and can see and
  cancel all tasks of that client. Keep the credentials as safe as an
  administrator's, list only clients of services that you trust, and remove
  a client from the list (and rotate its credentials) if they may have been
  disclosed.
* Service tokens are checked locally, so a token stays valid until it
  expires (`exp`), also if the client is disabled at the provider in the
  meantime. Removing the client from the list (with a restart of TESK API)
  takes effect at once.
* With `allowAnyAudience`, TESK API accepts every token that the issuer
  issues to that client ID, whatever its `aud`, also tokens that the client
  got for other services: whoever receives such a token (e.g. that other
  service), or obtains one that leaked from it, can use it at TESK API until
  it expires. Use it only for testing or while setting up a federation, and
  list the client's `audiences` in production. Each client is checked
  against its own audiences only, so listing an audience for one client
  doesn't make other clients' tokens for it valid.
* TESK API trusts every key that it gets from the issuer's discovery
  document or JWKS URI, so these must be `https://` URLs (plain `http://`
  only to `localhost`, e.g. for tests), so that nobody on the network path
  can give it keys of their own: with other URLs in the settings, TESK API
  doesn't start, and a discovery document with another `jwks_uri` is
  refused.
