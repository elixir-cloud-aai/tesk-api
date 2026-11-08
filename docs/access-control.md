# Access control

TESK API integrates with [Elixir AAI](https://www.elixir-europe.org/services/compute/aai) via [OpenID Connect](http://openid.net/connect/) protocol to authenticate and authorise requests. Authentication and authorisation are optional and can be switched off completely. When authentication is on, in order to perform any operation on tasks (get and/or create tasks) TESK API user will have to include a valid access token issued by Elixir AAI in the request header. To obtain such a token, a user will have to:
* have a valid account in Elixir AAI
* belong to a specific Elixir group (or, with [authorisation without groups](#authorisation-without-groups) on, have one of the configured values in a claim)
* have access to a registered Elixir client application, that was assigned - among others - `groupNames` scope (with authorisation without groups, the scope that releases the configured claim).

## Switching on/off authentication and authorisation
Authentication and authorisation can be globally switched on and off for TESK API using [Spring Profiles](https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-profiles.html). When the default `noauth` profile is active, all endpoints are unauthenticated (so the setting for `GET /service-info` below has no effect) and all (also previously) created TES tasks are visible to any user. New tasks are labelled as created by `anonymousUser` and no team ownership is assigned to new tasks. With `auth` profile activated, all the rules described below apply. Set the profile with `SPRING_PROFILES_ACTIVE` (see [Configuration](configuration.md#settings)).

## Authentication
The TESK API acts as a Resource Server in the OIDC contract, whereas Elixir AAI plays the role of Authorization Server. All of TESK API endpoints, except the [public ones](#public-endpoints), are secured, which means that in order to reach them, the request has to contain a valid _access_token_ issued by Elixir AAI, placed in `authorization: Bearer` header. 

### Public endpoints
These `GET` and `HEAD` requests need no token, also with the `auth` profile:
* `GET /service-info` (under the base path of the TES API), so that registries and clients can see what a service offers and how to get access. This is the default. To require a valid token for it (no group membership needed), set the environment variable `TESK_API_SERVICE_INFO_PUBLIC` to `false` (in the [Helm chart of TESK](https://github.com/elixir-cloud-aai/TESK): `auth.service_info_public`, which needs TESK API 1.2.0 or later). Service info can name your organisation, contact and storage locations: check the file that `TESK_API_SERVICE_INFO_LOCATION` points to (by default the built-in [`service-info.yaml`](https://github.com/elixir-cloud-aai/tesk-api/blob/dev/src/main/resources/service-info.yaml); see [Configuration](configuration.md#service-info)) if that matters for your deployment.
* The API description, and the Swagger UI if it is on (`TESK_API_SWAGGER_UI_ENABLED`; see [API](api.md#swagger-ui)).

A request that does carry a token has it validated on these too: with an invalid token, the answer is `HTTP 401 Unauthorized`.

### Obtaining a token
At the moment (subject to change) TESK API does not require the access token to be obtained using any specific client (application). Also the token audience is not currently checked (it is not clear, if Elixir AAI supports defining a token audience). Therefore, a valid token has the following properties:
* Access token
* Not expired
* Obtained by a user using any registered Elixir AAI client
* Obtained by a user using any OIDC Flow
* Obtained by a user using a client, that was granted `groupNames` scope (see [Authorisation](#authorisation))
* Containing any audience
* If Elixir AAI introduces groups-clients binding (presenting only those group names to clients, that where mentioned during client registration - currently each client receives all groups, as long as it has the scope `groupNames`), the token will have to be obtained using a client, which can receive those groups that are needed during Authorisation (see [below](#authorisation))

#### Built-in client
A valid token can be obtained using an OIDC client built in Swagger UI at the TESK API homepage, if the Swagger UI is on (`TESK_API_SWAGGER_UI_ENABLED` set to `true`; it is off by default). In order to use the client, it has to be configured, by placing Client ID - registered at Elixir AAI with Implicit Grant - in the API environment variable `TESK_API_SWAGGER_OAUTH_CLIENT_ID` (authorisation code grant also possible, with `TESK_API_SWAGGER_OAUTH_IMPLICIT` set to `false`, but discouraged). The Swagger UI runs in the browser, so it is a public client: register it at your identity provider as a public client, without a client secret. TESK API doesn't pass a client secret to the Swagger UI; `TESK_API_SWAGGER_OAUTH_CLIENT_SECRET` is deprecated and ignored. The Swagger UI of TESK API can't use PKCE yet, so with the authorisation code grant your identity provider must allow it for public clients without PKCE; otherwise use the implicit grant. Unless groups-clients relationship is introduced, token obtained using Swagger UI from one environment can be used to make calls to API installed elsewhere.   

### Token validation
TESK API validates the token by hitting Elixir AAI `user_info` endpoint on each request and extracting information returned from that endpoint. If user info is returned without error, token is considered valid. Invalid token or lack of thereof in `authorisation: Bearer` header will result in `HTTP 401 Unauthorized` error. 

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
`TESK_API_AUTHORISATION_PARENT_GROUP` | Full name of the base group (with default `elixir:` prefix, which is added by Elixir to all group names) | `elixir:GA4GH:GA4GH-CAP`
`TESK_API_AUTHORISATION_ENV_SUBGROUP` | Name of the subgroup representing environment (aka Environment Group) | `EBI` (Full group name:  `elixir:GA4GH:GA4GH-CAP:EBI`)
`TESK_API_AUTHORISATION_ADMIN_SUBGROUP` | Name of the subgroup representing admins (super admins or team admins) | `ADMIN` (Full group name of super-admins:  `elixir:GA4GH:GA4GH-CAP:EBI:ADMIN`)

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
label again (see [Upgrading](upgrading.md#to-the-next-release)):

Environment variable | Meaning | Default
--- | --- | ---
`TESK_API_AUTHORISATION_LEGACY_USER_LABELS` | `true`: a task with only the old label `creator-user-id` belongs to the users whose `sub`, with `@` replaced by `_`, is the label's value. Tasks with the label `creator-user-hash` still need the exact `sub`. Team members then list all tasks of their teams through Kubernetes, and users authorised by the claim only all tasks without a team; TESK API checks the creator of each, so pages can be shorter or empty, and listing reads more objects. | `false`

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
