# Access control

TESK API integrates with [Elixir AAI](https://www.elixir-europe.org/services/compute/aai) via [OpenID Connect](http://openid.net/connect/) protocol to authenticate and authorise requests. Authentication and authorisation are optional and can be switched off completely. When authentication is on, in order to perform any operation on tasks (get and/or create tasks) TESK API user will have to include a valid access token issued by Elixir AAI in the request header. To obtain such a token, a user will have to:
* have a valid account in Elixir AAI
* belong to a specific Elixir group (or, with [authorisation without groups](#authorisation-without-groups) on, have one of the configured values in a claim)
* have access to a registered Elixir client application, that was assigned - among others - the scope that releases the [groups claim](#configuration) (by default `eduperson_entitlement`) (with authorisation without groups, the scope that releases the configured claim).

## Switching on/off authentication and authorisation
Authentication and authorisation can be globally switched on and off for TESK API using [Spring Profiles](https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-profiles.html). When the default `noauth` profile is active, all endpoints are unauthenticated (so the setting for `GET /service-info` below has no effect) and all (also previously) created TES tasks are visible to any user. New tasks are labelled as created by `anonymousUser` and no team ownership is assigned to new tasks. With `auth` profile activated, all the rules described below apply. Set the profile with `SPRING_PROFILES_ACTIVE` (see [Configuration](configuration.md#settings)).

## Authentication
The TESK API acts as a Resource Server in the OIDC contract, whereas Elixir AAI plays the role of Authorization Server. All of TESK API endpoints, except the [public ones](#public-endpoints), are secured, which means that in order to reach them, the request has to contain a valid _access_token_ issued by Elixir AAI, placed in `authorization: Bearer` header. 

### Public endpoints
These `GET` and `HEAD` requests need no token, also with the `auth` profile:
* `GET /service-info` (under the base path of the TES API), so that registries and clients can see what a service offers and how to get access. This is the default. To require a valid token for it (no group membership needed), set the environment variable `TESK_API_SERVICE_INFO_PUBLIC` to `false` (in the [Helm chart of TESK](https://github.com/elixir-cloud-aai/TESK): `auth.serviceInfoPublic`, from chart 0.3.0, which needs TESK API 1.2.0 or later). Service info can name your organisation, contact and storage locations: check the file that `TESK_API_SERVICE_INFO_LOCATION` points to (by default the built-in [`service-info.yaml`](https://github.com/elixir-cloud-aai/tesk-api/blob/dev/src/main/resources/service-info.yaml); see [Configuration](configuration.md#service-info)) if that matters for your deployment.
* The API description, and the Swagger UI if it is on (`TESK_API_SWAGGER_UI_ENABLED`; see [API](api.md#swagger-ui)).

A request that does carry a token has it validated on these too: with an invalid token, the answer is `HTTP 401 Unauthorized`.

### Obtaining a token
At the moment (subject to change) TESK API does not require the access token to be obtained using any specific client (application). Also the token audience is not currently checked (it is not clear, if Elixir AAI supports defining a token audience). Therefore, a valid token has the following properties:
* Access token
* Not expired
* Obtained by a user using any registered Elixir AAI client
* Obtained by a user using any OIDC Flow
* Obtained by a user using a client, that was granted the scope that releases the [groups claim](#configuration) (by default `eduperson_entitlement`) (see [Authorisation](#authorisation))
* Containing any audience
* If Elixir AAI introduces groups-clients binding (presenting only those group names to clients, that where mentioned during client registration - currently each client receives all groups, as long as it has the scope that releases the groups claim), the token will have to be obtained using a client, which can receive those groups that are needed during Authorisation (see [below](#authorisation))

#### Built-in client
A valid token can be obtained using an OIDC client built in Swagger UI at the TESK API homepage, if the Swagger UI is on (`TESK_API_SWAGGER_UI_ENABLED` set to `true`; it is off by default). In order to use the client, it has to be configured, by placing Client ID - registered at Elixir AAI with Implicit Grant - in the API environment variable `TESK_API_SWAGGER_OAUTH_CLIENT_ID` (authorisation code grant also possible, with `TESK_API_SWAGGER_OAUTH_IMPLICIT` set to `false`, but discouraged). The Swagger UI runs in the browser, so it is a public client: register it at your identity provider as a public client, without a client secret. TESK API doesn't pass a client secret to the Swagger UI; `TESK_API_SWAGGER_OAUTH_CLIENT_SECRET` is deprecated and ignored. The Swagger UI of TESK API can't use PKCE yet, so with the authorisation code grant your identity provider must allow it for public clients without PKCE; otherwise use the implicit grant. Unless groups-clients relationship is introduced, token obtained using Swagger UI from one environment can be used to make calls to API installed elsewhere.

Environment variable | Meaning | Default
--- | --- | ---
`TESK_API_SWAGGER_OAUTH_CLIENT_ID` | Client ID of the Swagger UI at your identity provider. | `changeme`
`TESK_API_SWAGGER_OAUTH_IMPLICIT` | `true`: the Swagger UI gets tokens with the implicit grant, from the authorisation endpoint. `false`: with the authorisation code grant, from the authorisation and token endpoints, without a client secret and without PKCE (see above). | `true`
`TESK_API_SWAGGER_OAUTH_AUTHORIZATION_ENDPOINT` | Authorisation endpoint of the identity provider. | `https://login.elixir-czech.org/oidc/authorize`
`TESK_API_SWAGGER_OAUTH_TOKEN_ENDPOINT` | Token endpoint of the identity provider; used only with the authorisation code grant. | `https://login.elixir-czech.org/oidc/token`
`TESK_API_SWAGGER_OAUTH_SCOPES` | Comma-separated scopes that the Swagger UI offers, each as `<scope>:<description>` (the first `:` separates them). The user picks among them when logging in; the token needs a scope that releases the groups claim (or the claim for [authorisation without groups](#authorisation-without-groups)). | `openid`, the [groups claim](#configuration) (`eduperson_entitlement`), `profile` and `email`

For the authorisation code grant, register the Swagger UI as a public client
with the authorisation code grant, and with the redirect URI
`<base URL of TESK API>/swagger-ui/oauth2-redirect.html`; your identity
provider must issue tokens to it without a client secret and without PKCE.

### Token validation
TESK API validates the token by hitting Elixir AAI `user_info` endpoint on each request and extracting information returned from that endpoint. If user info is returned without error, token is considered valid. Invalid token or lack of thereof in `authorisation: Bearer` header will result in `HTTP 401 Unauthorized` error. Tokens of allow-listed services are checked locally instead, if that is on (see [Tokens of services](#tokens-of-services)).

### Integrating with a different OIDC provider
We have not tested TESK API integration with a different OIDC provider than Elixir AAI, but since standard Spring OAuth library is used, it should be possible (just point `security.oauth2.resource.user-info-uri`, or `SECURITY_OAUTH2_RESOURCE_USER_INFO_URI`, to a proper URL), provided that the other Authorization Server provides information about the user in the same format that Elixir does. Otherwise changes to [GroupNamesSecurityExtractor](https://github.com/elixir-cloud-aai/tesk-api/blob/dev/src/main/java/uk/ac/ebi/tsc/tesk/config/security/GroupNamesSecurityExtractor.java) and [ElixirPrincipalExtractor](https://github.com/elixir-cloud-aai/tesk-api/blob/dev/src/main/java/uk/ac/ebi/tsc/tesk/config/security/ElixirPrincipalExtractor.java) will be necessary.

## Authorisation
TESK API uses Elixir AAI group membership to authorise users (and optionally a claim, see [Authorisation without groups](#authorisation-without-groups)). Elixir recognises group managers and members, but only includes information about the latter in `user_info` endpoint response. Therefore a nested structure of groups is used by us to reflect different roles of users.
### Group structure
Group level | Example (from our own environments) | Meaning | Multiplicity
------------ |---| -------------|---
Base Group | GA4GH:GA4GH-CAP | Base group, representing all TESK environments. All other groups are subgroups of this groups. Does not have meaning on its own. | There can be only one per TESK installation.
Environment Group| GA4GH:GA4GH-CAP:EBI | Direct subgroup of base group, representing TESK environment/installation (EBI or CSC for environments managed by us) | There can be only one per TESK installation.
Super Admin Group | GA4GH:GA4GH-CAP:EBI:ADMIN | Direct subgroup - named ADMIN - of environment group, representing admins of the whole TESK installation (in our case Admins of TESK installed in EBI) | There can be only one per TESK installation.
Team Group | GA4GH:GA4GH-CAP:EBI:SDO | Direct subgroup - named anything else than ADMIN - of environment group. Represents a group of co-workers. | There can be many for each TESK installation with any names other than ADMIN.
Team Admin Group | GA4GH:GA4GH-CAP:EBI:SDO:ADMIN | Direct subgroup - named ADMIN - of team group. Represents a group of users, being admins of a team group.| One per team group; many per TESK installation.

### Endpoint permissions

Endpoint | Objective | Condition 
------------ |---|--
Create a task | A task must be created by a specific user and for up to one specific team (no team or one team). | User must be a member of _any_ Team Group or a Super Admin to create a task. If a user is a member of more than one Team Group, a user can specify, for which team to create a task (by putting team name in `tags['GROUP_NAME']`. If no team is specified, task will still be created for a randomly chosen team. If a user does not belong to any team (is a Super Admin only), task is still created, but does not belong to any team (only other Super Admins will be able to see it).
Get a task | A team member can only see tasks, that were created by him/her. A team admin can see all tasks created for his/her team. A super admin can see all tasks. | User must be a member of _the_ Team Group, that the task was created for AND be _the_ task creator OR user must be an admin of _the_ Team Group, that _the_ task was created for OR user must be a Super Admin.
Cancel a task | User can cancel those tasks that are visible to him/her (as for `Get a task`). | User must be a member of _the_ Team Group, that _the_ task was created for AND be _the_ task creator OR user must be an admin of _the_ Team Group, that _the_ task was created for OR user must be a Super Admin.
List tasks | User can list those task that are visible to him (as for `Get a task`). | User must be a member of _any_ Team Group OR user must be an admin of _any_ Team Group OR user must be a super admin to access `List tasks` enpoint. The response contains those tasks, that are visible to the user (rules from `Get a task` apply).

### Examples
* User '123' with group `GA4GH:G4GH-CAP:EBI:SDO` creates a task at TESK installed in `EBI`. Task created by user 123, for team SDO.
* User '123' with groups `GA4GH:G4GH-CAP:EBI:SDO`, `GA4GH:G4GH-CAP:EBI:TEST` creates in `EBI` a task without tags. Task created by user 123, for - randomly chosen - team SDO.
* User '123' with groups `GA4GH:G4GH-CAP:EBI:SDO`, `GA4GH:G4GH-CAP:EBI:TEST` creates a task with tags: ```{"tags" : {"GROUP_NAME" : "TEST"}}``` Task created by user 123, for team TEST.
* User '123' with group `GA4GH:G4GH-CAP:EBI` creates a task at TESK installed in `EBI`. Forbidden, no task created --> no team membership
* User '123' with group `GA4GH:G4GH-CAP:EBI:ADMIN` creates a task at TESK installed in `EBI`. Task created by user 123, without group assignment.
* User '123' with group `GA4GH:G4GH-CAP:EBI:SDO:ADMIN` creates a task at TESK installed in `EBI`. Elixir additionally treats such a user as a member of all parent groups, so the user also belongs to a group `GA4GH:G4GH-CAP:EBI:SDO` - so task will be created for group SDO (otherwise - if not Elixir special handling of nested membership and only taken TESK API rules into account - would be Forbidden).
* User '123' with group `GA4GH:G4GH-CAP:CSC:SDO` creates a task at TESK installed in `EBI`. Forbidden - no permissions to create tasks in `EBI`.
* User '123' with group `GA4GH:G4GH-CAP:EBI:SDO` gets a task created by user 123, for team SDO via TESK installed in `EBI`. Success.
* User '124' with group `GA4GH:G4GH-CAP:EBI:SDO` gets a task created by user 123, for team SDO via TESK installed in `EBI`. Forbidden (not his/her task).
* User '123' with group `GA4GH:G4GH-CAP:EBI` only, gets a task created by user 123, for team SDO via TESK installed in `EBI`. Forbidden (not SDO group member anymore).
* User '123' with group `GA4GH:G4GH-CAP:EBI:SDO:ADMIN`, gets a task created by user 123, for team SDO via TESK installed in `EBI`. Success.
* User '124' with group `GA4GH:G4GH-CAP:EBI:SDO:ADMIN`, gets a task created by user 123, for team SDO via TESK installed in `EBI`. Success (team group admin sees all team tasks).
* User '124' with group `GA4GH:G4GH-CAP:EBI:ADMIN`, gets a task created by user 123, for team SDO via TESK installed in `EBI`. Success (super admin sees everything). 
* User '123' with group `GA4GH:G4GH-CAP:EBI:SDO` gets a task list. Success. List contains all tasks created for SDO by user 123.
* User '123' with group `GA4GH:G4GH-CAP:EBI` only, gets a task list. Forbidden (not a team member, neither team admin, nor super admin).
* User '123' with group `GA4GH:G4GH-CAP:EBI:SDO:ADMIN`, gets a task list. Success. List contains all tasks created for team SDO by all users.
* User '124' with group `GA4GH:G4GH-CAP:EBI:ADMIN`, gets a task list. Success. List contains all tasks created for all/no teams by all users.
* User '123' with groups `GA4GH:G4GH-CAP:EBI:TEST` and `GA4GH:G4GH-CAP:EBI:SDO:ADMIN`, gets a task list. Success. List contains all tasks created for team TEST by user 123 and also all tasks created for team SDO by all users.

### Configuration
Parts of Elixir groups 'authorisation framework' - particularly base group name, environment subgroup name and ADMIN subgroup name - are configurable in [the same way](configuration.md) as all settings of TESK API.

Environment variable | Meaning | Default
------------ |---|--
`TESK_API_AUTHORISATION_GROUPS_CLAIM` | Name of the claim of the user info with the user's group memberships (a string or a list of strings). The default [scopes of the Swagger UI](#built-in-client) request a scope of the same name. | `eduperson_entitlement`
`TESK_API_AUTHORISATION_GROUP_PREFIX` | Text before the group names in that claim. | `urn:geant:elixir-europe.org:group:elixir:`
`TESK_API_AUTHORISATION_GROUP_SUFFIX` | Text after the group names in that claim. | `#perun.elixir-czech.cz`
`TESK_API_AUTHORISATION_DELIMITER` | Separator of the group levels in the names below. Keep `:`: TESK API recognises the team and team admin groups by `:` whatever this setting is. | `:`
`TESK_API_AUTHORISATION_PARENT_GROUP` | Name of the Base Group, without prefix and suffix. | `GA4GH:GA4GH-CAP`
`TESK_API_AUTHORISATION_ENV_SUBGROUP` | Name of the Environment Group below the Base Group. | `EBI`
`TESK_API_AUTHORISATION_BASE_GROUP` | Full name of the Environment Group, without prefix and suffix; set it instead of the two settings above to give it directly. | the Base Group, the delimiter and the Environment Group: `GA4GH:GA4GH-CAP:EBI`
`TESK_API_AUTHORISATION_ADMIN_SUBGROUP` | Name of the subgroups of admins (super admins below the Environment Group, team admins below a Team Group). | `ADMIN`

With the defaults, the claim `eduperson_entitlement` of a member of the team
`SDO` has the values
`urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI#perun.elixir-czech.cz`
(the Environment Group, which creating and cancelling tasks need) and
`urn:geant:elixir-europe.org:group:elixir:GA4GH:GA4GH-CAP:EBI:SDO#perun.elixir-czech.cz`;
an admin of that team has `…:EBI:SDO:ADMIN#perun.elixir-czech.cz`, a super
admin `…:EBI:ADMIN#perun.elixir-czech.cz`. Team names can't contain `:`.

The settings above (`…_GROUP_PREFIX`, `…_PARENT_GROUP`, `…_ENV_SUBGROUP`,
`…_BASE_GROUP`, `…_DELIMITER`, `…_ADMIN_SUBGROUP` and `…_GROUP_SUFFIX`) are
compared as plain text, character by character: characters such as `.`, `*`
or `|` have no special meaning, so only group names with exactly the
configured text count, and patterns (e.g. `(A|B)` for two groups) don't work.
Only the team name in between can be any text without `:`.

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
the annotation. The team of a task is the label `creator-group-name`.

Tasks created by earlier versions of TESK API have the label `creator-user-id`
instead, with the `sub`'s `@` replaced by `_`; their `USER_ID` shows that
value. That encoding gives different IDs the same value (e.g. `a@b` and
`a_b`), and doesn't fit IDs longer than 63 characters or with other
characters, so TESK API doesn't match these tasks to users by default: team
admins and super admins see them as before, their creators don't. For a
transition after the upgrade, the following setting matches them by the old
label again (see [Upgrading](upgrading.md#to-120)):

Environment variable | Meaning | Default
--- | --- | ---
`TESK_API_AUTHORISATION_LEGACY_USER_LABELS` | `true`: a task with only the old label `creator-user-id` belongs to the users whose `sub`, with `@` replaced by `_`, is the label's value. Tasks with the label `creator-user-hash` or the annotation `creator-user-id` still need the exact `sub`: a task with only one of the two names no creator, also with this setting. Team members then list all tasks of their teams through Kubernetes, and users authorised by the claim only all tasks without a team; TESK API checks the creator of each, so pages can be shorter or empty, and listing reads more objects. | `false`

## Authorisation without groups

Some OpenID Connect providers (e.g. LS Login) give no group memberships that
fit the [group structure](#group-structure). For them, TESK API can authorise
users by a claim of the user info instead. The setting is off by default; with
it off, nothing changes. It needs the `auth` profile.

Environment variable | Meaning | Default
--- | --- | ---
`TESK_API_AUTHORISATION_CLAIM_ENABLED` | `true`: users with one of the values below in the claim below are authorised without groups. | `false`
`TESK_API_AUTHORISATION_CLAIM_NAME` | Name of the claim in the user info, e.g. `eduperson_entitlement` or `eduperson_assurance`. Required if the setting is on. | none
`TESK_API_AUTHORISATION_CLAIM_VALUES` | Comma-separated values of the claim; a user needs at least one of them. The claim can be a string or a list of strings; values are compared exactly (also in case). Required if the setting is on. | none

If the setting is on without a claim name or without a non-blank value, TESK
API doesn't start.

Users get their tokens from a client that the provider must allow to release
the claim (e.g. the scope `eduperson_entitlement` or `eduperson_assurance`);
without it, the user info lacks the claim and every request is refused with
`403 Forbidden`.

With the setting on, a user with one of the values (a "claim user"):

* may create tasks. The tasks belong to the user only, without a team: a
  task with `tags['GROUP_NAME']` is refused (`403 Forbidden`), unless the
  user may create tasks for that team by the group rules.
* may list, get and cancel their own tasks without a team, and no others.

Group rules are unchanged and come first: a team member, team admin or super
admin keeps the rights described [above](#endpoint-permissions), whether or
not they have the claim. Super admins (the Super Admin Group) see all tasks,
including those of claim users; team admins don't see them, as they belong to
no team.

Listing follows the group rules first, too: a user who is a team member or
team admin and has the claim lists the tasks of their teams, but not their
own tasks without a team, although they may still get and cancel these by
ID. This happens to tasks that a user created with the claim only before
joining a team, and to tasks of team admins who aren't team members (these
are created without a team). A team member who loses the membership but has
the claim lists only their tasks without a team.

### Security implications

* Everyone who gets one of the values from your provider can run any
  container image with any command on your cluster, within the resources
  that tasks may request. Choose a claim and values that your provider sets
  per user and that users can't set themselves, e.g. an entitlement that an
  administrator of your community grants, or an assurance level that your
  provider checks. A value that every account has (e.g. a low assurance
  level, or the provider's default entitlement) opens TESK to all of its
  users.
* Values are compared exactly, so list every value that you accept: with
  `eduperson_assurance`, a higher level doesn't imply a lower one.
* TESK API reads the claim from the user info endpoint on each request, so a
  value revoked at the provider takes effect at the next request. Tasks that
  are already running keep running.
* Users are identified by `sub`, compared exactly, as for group members (see
  [Task creators](#task-creators)).

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
* `exp`, which the token must have, and `nbf`, if it has one, with
  a leeway of `…_CLOCK_SKEW_SECONDS`;
* the header `typ`, which must be `at+jwt` ([RFC 9068, section
  4](https://datatracker.ietf.org/doc/html/rfc9068#section-4)), so that ID
  tokens aren't accepted as access tokens (unless
  `…_REQUIRE_ACCESS_TOKEN_TYPE` is `false`);
* the audience: if `…_AUDIENCE` is set, `aud` must be or contain it.

If all of these hold, the request is authenticated as that client, without
a call to the user info endpoint. If one fails, the request is refused with
`401 Unauthorized`, also without a call to the user info endpoint.

All other tokens go the existing way, through the user info endpoint, so
user tokens keep working as before: tokens that aren't JWTs, JWTs of other
issuers or of clients that aren't listed, and tokens of users obtained
through a listed client (their `sub` is the user's). A client that isn't
listed is refused by the user info endpoint (`401 Unauthorized`) or, if the
endpoint answers, gets the rights of a user without groups (usually none:
`403 Forbidden`).

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
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_CLIENT_IDS` | Comma-separated IDs of the clients whose tokens are accepted, compared exactly. Required if the setting is on. | none
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_AUDIENCE` | If set, the token's `aud` must be or contain this value. Recommended: register the clients so that their tokens for TESK get an audience of their own (e.g. the URL of your TESK API), and set it here, so that tokens that a client got for other services aren't accepted. | none (any audience)
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_JWKS_URI` | JWKS URI of the issuer, if it shouldn't come from the discovery document. | from discovery
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_CLIENT_CLAIM` | Claim that names the client. Some providers use `azp`. | `client_id`
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_REQUIRE_ACCESS_TOKEN_TYPE` | `true`: the header `typ` must be `at+jwt`. Set it to `false` only for providers that don't set it. | `true`
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_REQUIRE_CLIENT_SUBJECT` | `true`: the token's `sub` must be the client ID, as in tokens of the client credentials grant; tokens of users obtained through a listed client go through the user info endpoint, as user tokens. `false`: any `sub`; then every token that a user gets through a listed client is accepted as the client's. | `true`
`TESK_API_AUTHORISATION_SERVICE_CLIENTS_CLOCK_SKEW_SECONDS` | Leeway for `exp` and `nbf`, in seconds. | `60`

If the setting is on without an issuer or without a non-blank client ID, or
with a negative leeway, TESK API doesn't start. The discovery document and
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
The defaults fit these tokens: set the issuer, the client IDs, and the
audience that you registered for TESK.

### Authorisation of service clients

A listed client is a principal of its own: its ID is the client ID, and it
has no groups, so no group rules apply. It:

* may create tasks. The tasks belong to the client only, without a team: a
  task with `tags['GROUP_NAME']` is refused (`403 Forbidden`).
* may list, get and cancel the tasks that it created, and no others, also
  none of other listed clients.

Tasks of a client record it as their creator as for users (see [Task
creators](#task-creators)): the label `creator-user-hash` has the hash of the
client ID, the annotation `creator-user-id` and `logs[0].metadata.USER_ID`
the client ID. Super admins see these tasks, as all tasks; team admins
don't, as they belong to no team.

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
* Without `…_AUDIENCE`, TESK API accepts a listed client's tokens for any
  audience, also tokens that the client got for other services: whoever
  receives such a token (e.g. that other service) can use it at TESK API
  until it expires. Set the audience if your provider lets you choose it.
* TESK API trusts every key that it gets from the issuer's discovery
  document or JWKS URI. Use `https://` URLs for the issuer and
  `…_JWKS_URI` (TESK API doesn't enforce it), so that nobody on the network
  path can give it keys of their own.
