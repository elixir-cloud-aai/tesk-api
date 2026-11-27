# Development

TESK API is a [Spring Boot](https://spring.io/projects/spring-boot) 4.1
application (Spring Framework 7, Spring Security 7, Jackson 3) on Java 21,
built with Maven 3.9 (through the wrapper `./mvnw`, which downloads the
version pinned in `.mvn/wrapper/maven-wrapper.properties`). The server stub is
generated from the TES specification with the [OpenAPI Generator Maven
plugin](https://github.com/OpenAPITools/openapi-generator/tree/master/modules/openapi-generator-maven-plugin)
(see [Generating a new API stub](#generating-a-new-api-stub)),
[springdoc-openapi](https://springdoc.org/) serves the API description and
the Swagger UI, and the [Kubernetes Java
client](https://github.com/kubernetes-client/java) talks to the Kubernetes
API.

The JSON of the API and of the tasks stored in Kubernetes is written and read
with Jackson 3 with the defaults of Jackson 2
(`spring.jackson.use-jackson2-defaults=true` in `application.properties`), so
that it stays as it was with Spring Boot 2.7. The model classes keep the
annotations of Jackson 2 (`com.fasterxml.jackson.annotation`), which Jackson 3
reads.

The Kubernetes client needs newer versions of some libraries than Spring Boot
manages (SnakeYAML, Gson, Commons Lang and Commons Codec): `pom.xml` sets them
to those of the client's parent POM (`io.kubernetes:client-java-parent`), so
update them together with the client. The models of the client refuse JSON
with fields they don't know; `KubernetesJson` installs a Gson that ignores
them (see [Deployment](deployment.md#kubernetes)), so test fixtures of
Kubernetes objects may leave out fields that Kubernetes requires. The
executors' jobs in the taskmaster's input are written without the empty
lists and maps of the client's models (`GSON_WITHOUT_EMPTY_COLLECTIONS`).

The wrapper is the `only-script` type of the Apache Maven Wrapper, so there is
no binary `maven-wrapper.jar` in the repository. It needs `curl` or `wget` and
`unzip` (without `unzip` it would fetch the `.tar.gz`, which does not match the
checksum), and it checks the downloaded Maven distribution against
`distributionSha256Sum` in `.mvn/wrapper/maven-wrapper.properties`; a mismatch
stops the build.

Dependabot updates the Maven version and the checksum of the wrapper together
(in the `maven` group). To bump them by hand, change both, or every build
fails:

1. Download `apache-maven-<version>-bin.zip` from Maven Central and check it
   against the `.sha512` file next to it.
2. Compute its SHA-256 (`sha256sum`).
3. Put the new version in `distributionUrl` and the sum in
   `distributionSha256Sum`. If you regenerate the scripts with
   `./mvnw wrapper:wrapper -Dtype=only-script -Dmaven=<version>`, check that
   `distributionSha256Sum` is still there and has the new sum.

## Building and testing

To build TESK API and run the unit tests (JUnit, Surefire, in
`src/test/java`) and the integration tests (Failsafe, in
`src/integration-test/java`; the Kubernetes API and the OpenID Connect
provider are mocked with WireMock, so no cluster is needed):

```bash
./mvnw clean verify
```

The tests are written for JUnit Jupiter (JUnit 5 and later; no JUnit 4 or
vintage engine): `@ExtendWith` and `@RegisterExtension` instead of runners and
rules, e.g. `WireMockExtension` for WireMock servers (built with
`failOnUnmatchedRequests(true)`, so that a request that no stub matches fails
the test) and Spring Boot's
`OutputCaptureExtension` for logs. Spring Boot's test annotations
(`@SpringBootTest`, `@WebMvcTest`, …) include the Spring extension. Settings
that the code reads from environment variables are read through Spring's
`Environment` (e.g. those of the security context, `CONFIGMAP` and the like),
so that tests set them with a `MockEnvironment` or test properties rather than
by changing the process's environment. Surefire and Failsafe load Mockito as a
Java agent at startup (`-javaagent` in their `argLine`): Java 21 warns about
agents that are loaded later, as Mockito would load itself, and later Java
versions refuse them.

### Test coverage

The unit and integration tests together must cover all lines and branches:
`./mvnw clean verify` fails otherwise (`check` of `jacoco-maven-plugin` in
`pom.xml`, on the merged data of both in `target/jacoco-merged.exec`), and so
does Codecov (`codecov.yml`), to which CI sends the merged report. Not measured
are only:

- the files that openapi-generator generates from the TES specification (listed
  in `src/main/java/.openapi-generator/FILES`, and excluded from formatting and
  lint as well): data classes, and the API stubs, whose default methods the
  controllers override. Keep the list in `pom.xml` the same as that file;
- `TeskApplication`, whose `main` only starts Spring Boot, as the tests do.

JaCoCo writes the reports of the unit tests to `target/site/jacoco/`, those
of the integration tests to `target/site/jacoco-it/`, and that of both
together, which the check uses and CI sends to Codecov, to
`target/site/jacoco-merged/` (`index.html` for a browser, `jacoco.xml` for
tools). To see what the tests miss, open
`target/site/jacoco-merged/index.html` after `./mvnw clean verify`.

Cover new code with tests that check its behaviour, rather than excluding it;
code that no test can reach is usually dead and can go.

### Access matrix

The integration tests in `src/integration-test/java/uk/ac/ebi/tsc/tesk/accessmatrix/`
pin the target behaviour of the access control redesign: who may call which
operation, with which answer. They are its acceptance tests, and they run
against the current code as well, where some answers differ until the
redesign's changes are in:

- `src/integration-test/resources/access-matrix/matrix.txt` is the caller x
  operation matrix of the design: one row per kind of caller (anonymous, users
  with and without `EOC-Compute`, former members, service clients, invalid
  tokens, exchanged tokens, failures of the decision service and of the user
  info endpoint), one column per operation (service info, create, get in the
  three views, list, cancel). `AccessMatrixIT` reads the table and runs one
  test per row, caller variant (e.g. row 12's kinds of invalid tokens) and
  operation; keep the table the same as the design's. Further tests cover the
  variants the design names next to the table: the stamped owner and actor,
  missing and foreign tasks alike, page tokens of other owners, service info
  that needs a token (`AccessMatrixServiceInfoPrivateIT`), and the decision
  service asked for all operations (`AccessMatrixAllOperationsIT`, also with
  the list scopes, a widening policy and failing closed) or failing open
  (`AccessMatrixFailOpenIT`), each with the decision service down, timing
  out and answering garbage.
- `src/integration-test/resources/access-matrix/pending.csv` lists the cells
  that the current code doesn't meet yet: configuration, case, operation, the
  change (slice) of the redesign that makes the cell pass, and the status the
  code answers now. The test of a pending cell checks that the code still
  answers that status and still misses the target (a strict expected
  failure), and then ends as skipped, naming the slice. Once a change meets a
  cell's target, its test fails until the change removes the line; if a
  change alters the answer of a cell that stays pending, its test fails until
  the change updates the status.
- The tests run TESK with the `auth` profile against a mock OpenID Connect
  provider (`MockIdp`: discovery, keys, tokens signed as the callers need
  them, and a user info endpoint that answers like LS Login's), a mock
  decision service (`MockPdp`: a stand-in for the owner-only reference policy
  of [tes-authz](https://github.com/elixir-cloud-aai/tes-authz), with failure
  modes, recording the requests) and a mock Kubernetes API (`MockCluster`:
  running tasks of given owners). `Harness` holds the settings of the
  redesign (`tesk.api.auth.*` and `tesk.api.authz.*`; of the latter,
  `operations` and `failure` have no effect until T4b uses them). `Owners.of` is the owner ID that the mock tasks carry:
  `<iss>|<sub>` (the subject alone before T3b).

The changes (slices) of the redesign that `pending.csv` names, in their
order; each is a pull request of its own, stacked on the previous one, and
merged when it is ready:

Slice | Change | Cells it makes pass
--- | --- | ---
T2 | Removes the group model: only owners get, list and cancel their tasks, foreign tasks are 404 like missing ones; create is denied for everyone until T4a | Rows 2, 4-7, 15 (get, list, cancel)
T3b (done) | Authenticates with the token validator (#222): local checks of issuer, signature, audience, expiry and type, owners by issuer and subject, service info ignores the token | Rows 9-13, 17, 19 (all but create)
T4a (done) | Asks the decision service (AuthZEN, tes-authz) on create, with the entitlements from the user info endpoint; 503 if either fails | Create of rows 3, 7, 8, 18 and 19, the stamped owner, the user info answers (row 14 waits for T6)
T4b | Lets the decision service decide further operations (`tesk.api.authz.operations`), with list scopes and failure behaviour per operation | `all-operations` (but create); the `fail-open` cells already pass with T2 and T4a
T6 | Accepts exchanged tokens (RFC 8693) of trusted actors and records the actor | Row 16, the recorded actor

Until T6, tokens with an `act` claim are refused (`401`), so all cells of
rows 14-16 stay pending until T6 (see `pending.csv`), whatever slice the
table names for them. Failing open (`fail-open`) falls back to the owner-only decision,
never to allowing everything, and create never fails open.

Row 9 (unlisted service client) uses a client token whose audience TESK
accepts, so that only the missing listing makes the difference. A real LS
Login client token carries the client's own ID as its audience, which TESK
accepts for listed clients only, so an unlisted LS Login client gets 401 on
every operation (as in row 10; pinned by
`unlistedClient_withItsOwnAudience_isRefused`). With `serviceInfoPublic: false`, rows 16 and 17 get
on service info what they get on every other operation (403 and 401).

To run only these tests (the coverage check needs all tests, so skip it):

```bash
./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='AccessMatrix*IT' -Djacoco.skip=true
```

The skipped tests are the pending cells; the reason of each names its slice
and the target. A slice:

1. removes the lines of the cells it makes pass from `pending.csv` (their
   tests fail and name the line);
2. updates `today` of the cells whose answer it changes but which stay
   pending (their tests fail and name the new status), and adds the cells
   it breaks on purpose (e.g. T2 denies create until T4a), with the slice
   that fixes them;
3. removes the settings it deletes from `Harness` (T3b also switched
   `Owners.of` to `Owners.target`).

The case of a line is the caller's ID in `Callers` (the row's number and, for
rows with variants, a letter, e.g. `12a`), with `/none` or `/foreign` for
rows whose task column has both (row 2), or the name of a variant test (e.g.
`stamp-owner`). A new row of the table needs callers in `Callers`, or the
tests don't start.

## Formatting and linting

The Java code is formatted with
[google-java-format](https://github.com/google/google-java-format) in AOSP
style (4-space indentation, lines of at most 100 characters), through the
[Spotless](https://github.com/diffplug/spotless) Maven plugin, and linted with
[Checkstyle](https://checkstyle.org/) (rules in `checkstyle.xml`: likely bugs
and problematic constructs, e.g. missing braces or `equals` without
`hashCode`; the layout is left to the formatter; wildcard imports are
allowed). The generated files (listed in
`src/main/java/.openapi-generator/FILES`) are excluded from both. To format
the code, and to check it as the CI job `Lint` does (`./mvnw verify` checks
neither):

```bash
./mvnw spotless:apply
./mvnw spotless:check checkstyle:check
```

Like building and testing, these two commands need Java 21.

The commits that only reformatted the code are listed in
`.git-blame-ignore-revs`. GitHub skips them in its blame view; for `git blame`,
run once:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

## Token validation

The package `uk.ac.ebi.tsc.tesk.config.security.token` holds the token
validation (see [Token validation](access-control.md#token-validation) for
what it checks, and [Settings](access-control.md#settings) for its settings).
With the `auth` profile, `ResourceServerConfiguration` creates the validator
and plugs it into the resource server of Spring Security as an
`OpaqueTokenIntrospector` (`TokenValidatorIntrospector`): a refused token
gives `401` with `invalid_token`, unavailable keys give `503`
(`TokenCheckUnavailableException`, answered by
`CustomAuthenticationEntryPoint`), and an accepted token gives an
`AuthenticatedUser` whose user name is the owner ID. A `BearerTokenResolver`
ignores the `Authorization` header on the public paths, so that tokens there
are never checked.

- `TokenValidator` is the one interface for token validation: it takes the
  bearer token and returns the caller (`TokenPrincipal`), or throws an
  `InvalidTokenException` whose message is the reason (never the token; its
  caller is to answer `401`), or an `IssuerUnavailableException` if the token
  can't be checked now because the issuer's keys can't be fetched, at first or
  after the cached ones expired (its caller is to answer `503`, so that clients
  retry instead of treating it as a bad credential).
  `TokenValidators.create` selects the implementation by
  `tesk.api.auth.token-validator`. Only `jwt` exists; another kind (e.g.
  introspection of opaque tokens, RFC 7662) is a new implementation and a new
  value there, without changes to the callers.
- `JwtAccessTokenValidator` accepts JWT access tokens (RFC 9068) only: a
  single signed JWT whose `iss` is exactly one of the configured issuers,
  signed with one of the configured asymmetric algorithms (default `RS256`,
  `ES256`; `none` and HMAC can't be configured) by a key of that issuer (from
  its OpenID Connect discovery, or a configured JWKS URI; `IssuerVerifier`
  caches them, fetches them again at most once per interval, and lets requests
  that arrive during the first fetch wait for it, at most 15 seconds), with
  `typ`
  `at+jwt`, `exp`, `iat` and `sub` required, `nbf`, and a clock skew. Tokens
  with an `act` claim (delegation, RFC 8693) are refused for now.
- The caller is identified by issuer and subject together
  (`TokenPrincipal.owner()`: `<iss>|<sub>`). A token is a listed service
  client's if both the client claim (default `client_id`) and `sub` are the
  ID of a client in `service-clients`; it is then accepted if `aud` is or
  contains that client's ID (unless the client has `allow-any-audience`,
  below). Any other token is a user's: `aud` must be or
  contain one of the issuer's `audiences` (the client IDs of the front ends),
  and a `sub` that equals a listed client's ID is refused.
- `allow-any-audience` (per service client, default `false`) accepts that
  client's own tokens whatever their `aud`, or without one, like
  `allowAnyAudience` of the former service-client settings. It is opt-in and
  meant for testing, and for setting up a federation while the clients'
  audiences at the identity provider are still being sorted out. Its risk:
  every token that the issuer issues to that client is accepted, also tokens
  it got for other services, so a token that leaks from another service, or
  that another service replays, works at TESK API too. It applies to that
  client's own tokens only (not to users' tokens obtained through it, and not
  to other listed clients), and `JwtAccessTokenValidator` names each such
  client in a warning at startup.

The settings, as properties (environment variables in upper case, with
underscores for dots and list indices and without the dashes, e.g.
`TESK_API_AUTH_ISSUERS_0_URL` and
`TESK_API_AUTH_ISSUERS_0_SERVICECLIENTS_0_CLIENTID`):

```properties
tesk.api.auth.token-validator=jwt
tesk.api.auth.issuers[0].url=https://login.aai.lifescience-ri.eu/oidc/
tesk.api.auth.issuers[0].audiences=<front-end client ID>,<another one>
tesk.api.auth.issuers[0].service-clients[0].client-id=<service client ID>
# optional, with their defaults
tesk.api.auth.issuers[0].jwks-uri=
tesk.api.auth.issuers[0].client-claim=client_id
tesk.api.auth.issuers[0].service-clients[0].allow-any-audience=false
tesk.api.auth.algorithms=RS256,ES256
tesk.api.auth.require-access-token-type=true
tesk.api.auth.clock-skew-seconds=60
tesk.api.auth.max-token-age-seconds=86400
tesk.api.auth.timeout-seconds=10    # 1 to 60
```

Invalid settings (e.g. no issuer, an issuer with neither audiences nor service
clients, a plain `http` URL other than to the local host, a symmetric
algorithm, a timeout outside 1 to 60 seconds) make `JwtAccessTokenValidator` throw an `IllegalStateException`,
so that TESK API will refuse to start with them.

## Building the image and the docs

To build the image (the `Dockerfile` builds the application from source, so
nothing needs to be built first):

```bash
docker build --tag tesk-api .
```

To build these docs, with Python 3:

```bash
python -m venv .venv
.venv/bin/pip install --requirement docs/requirements.txt
.venv/bin/mkdocs serve
```

## Continuous integration

The CI workflow (`.github/workflows/ci.yml`) runs on pull requests and pushes
to `dev`: it checks the pull request title, lints the workflows, checks the
formatting and lint of the Java code, builds and tests TESK API (with
coverage to [Codecov](https://codecov.io/gh/elixir-cloud-aai/tesk-api)), builds
the docs strictly, starts the image in both profiles, and scans the
image for known vulnerabilities. For now, the scan only reports its findings
(to code scanning) and doesn't fail, until the dependencies are updated
([#60](https://github.com/elixir-cloud-aai/tesk-api/issues/60)); then it fails
on critical and high vulnerabilities that have a fix, except those accepted in
`.trivyignore.yaml`, each with a reason and an expiry date. The release
workflow's scan fails on them already. On pushes to `dev`, it publishes the
scanned and signed image as `<org>/tesk-api:dev` (and `sha-<commit>`), where
`<org>` is the variable `DOCKERHUB_ORG` (by default `elixircloud`); this needs
the secrets `DOCKERHUB_LOGIN` and `DOCKERHUB_TOKEN` in the environment
`dev-images`, which only allows branch `dev`. Forks without `DOCKERHUB_ORG`
run the checks only. The job `CI passed` sums up all the others and is the
only required check. The scan workflow (`.github/workflows/scan.yml`) scans
the image of the latest release weekly.

## Versioning and releases

Pull requests go to `dev` (squash merges; titles in the [Conventional
Commits](https://www.conventionalcommits.org/) format, at most 50
characters). For a release, `main` is moved forward to a commit of `dev`.
Pushing a signed, annotated version tag runs the release workflow
(`.github/workflows/release.yml`), which publishes the image to Docker Hub as
`<org>/tesk-api` and creates the GitHub release:

Tag | Image tags | Example
--- | --- | ---
`vX.Y.Z` | `X.Y.Z`, and `latest` if it is the highest release on `main` | `v1.2.0` publishes `1.2.0`
`vX.Y.ZrcN` (also `aN`, `bN`) | `X.Y.ZrcN` only | `v1.2.0rc1` publishes `1.2.0rc1`

The commit must be on `main`, CI must have passed for its push to `dev`, and
the versions must match the tag, so set them in a pull request first:

- `pom.xml`: `X.Y.Z`, also for the pre-releases of `X.Y.Z` (e.g., `1.2.0` for
  `v1.2.0rc1`);
- `CITATION.cff`: the tag without `v` (e.g., `1.2.0rc1` for `v1.2.0rc1`),
  with `date-released`;
- `docs/releases/<version>.md`, with the version as in `CITATION.cff`
  (e.g., `docs/releases/1.2.0rc1.md`): the curated release notes, in the
  organization's format: an intro paragraph saying what the release brings,
  with a link to the upgrade notes if operators or clients must act, then
  the sections `## Breaking changes`, `## Features`, `## Security`,
  `## Fixes`, `## Documentation` and `## Maintenance` (in this order, empty
  ones left out), holding bullets only, each ending with its issue and pull
  request numbers, e.g., `(#12, #34)`. Links must be absolute, as the notes
  are published on GitHub. The release workflow adds GitHub's generated
  notes; the shared action
  [`publish-release`](https://github.com/elixir-cloud-aai/.github/tree/main/actions/publish-release)
  describes the format and its checks.

`latest` goes by tags, so if a release fails, follow it with a higher version.
Only admins can create release tags, and nobody can move or delete them (tag
rulesets). Pushing and publishing the image each wait for an approval in the
`release` environment, which only allows version tags. The image is scanned
before it is tagged; images are signed with Sigstore and carry provenance
attestations.

Once the image is published, the job `Publish release` creates the GitHub
release of the tag (no manual step): the curated notes of the tagged commit,
then GitHub's generated notes under `## Commits` (without their
`## What's Changed` header, with "New Contributors"), and GitHub's
`**Full Changelog**` line last. Versions below 1.0.0 and `a`, `b` and `rc`
versions are pre-releases. The job needs no approval and no secrets, only
`contents: write`. `Prepare release` runs the same checks first (the notes,
the tag and its commit, published releases of the tag), so that a release
with missing or malformed notes fails before anything is published. It can't
see draft releases, so delete any draft of the tag before you push the tag.
If only `Publish release` fails, re-run the failed jobs (not all jobs: once
the GitHub release exists, `Prepare release` fails); a re-run passes without
changes if the earlier attempt created the release after all. `Publish
release` runs one release at a time, so that `latest` is set in order; if
several tags are released at once, GitHub can cancel a waiting run of the
job: re-run it the same way. Releases are
never edited afterwards: fix mistakes in the notes of the next release.

After publishing the image (without waiting for the GitHub release), the
workflow registers it, with the release tag, at
[Pubgrade](https://github.com/elixir-cloud-aai/Pubgrade) (`POST
/repositories/{id}/builds`, through Pubgrade's action
[`actions/notify`](https://github.com/elixir-cloud-aai/Pubgrade/tree/344a58adc1c871135f86c404eb33ed91011886ad/actions/notify)),
which notifies the deployments that subscribed to upgrades, also for
pre-releases, if they subscribed to those. The action masks the token and
retries a request only if it never reached Pubgrade, so that one run
registers the build at most once; re-running the job registers it again.
This needs Pubgrade's publish mode
([#33](https://github.com/elixir-cloud-aai/Pubgrade/issues/33)) and these
settings:

Setting | Kind | Where | Meaning
--- | --- | --- | ---
`PUBGRADE_URL` | Variable | Repository | Base URL of the Pubgrade API, with `https://`. Switches the notification on.
`PUBGRADE_REPOSITORY_ID` | Variable | Repository, or the `release` environment | The identifier from registering the repository in Pubgrade
`PUBGRADE_TOKEN` | Secret | The `release` environment | The access token from registering the repository in Pubgrade

Without `PUBGRADE_URL`, the job `Notify Pubgrade` is skipped, so releases
don't wait for its approval. Set `PUBGRADE_URL` for the repository, not in
the `release` environment: a job's condition is evaluated before the job
enters its environment, so a variable there doesn't switch the job on (and
would override the repository's URL within the job). An organization
variable would switch it on for every repository it is shared with, also
those without a Pubgrade token. With `PUBGRADE_URL`, the job waits for an
approval in the `release` environment, like pushing and publishing, and fails
if one of the other settings is missing or invalid: the identifier and the
token may only hold letters, digits, `.`, `~` and `-` (as Pubgrade issues
them). If only this job fails or is rejected, the image is published all the
same: fix the settings and re-run the job, or leave it; don't release a new
version for it. Unless it failed on an invalid setting or with "Could not
reach Pubgrade (curl exit status 6)" or "(… 7)" (no connection), Pubgrade
may have registered the build all the same: check the repository's builds
(`GET /repositories/{id}/builds`) before re-running it, as re-running
notifies the subscribed deployments again. Settings of the former `pubgrade`
environment are no longer read: move them as above.

## Generating a new API stub

The TES specification (v1.0) is linked as the submodule `tes`. After updating
it to a new version of the specification, run:

```bash
./mvnw clean generate-sources -P generate-swagger
```

This generates new versions of the model (`uk.ac.ebi.tsc.tesk.tes.model`) and
the API stub (`uk.ac.ebi.tsc.tesk.tes.api`). If the list of generated files
(`src/main/java/.openapi-generator/FILES`) changes, change the exclusions of
Spotless, Checkstyle (`checkstyle.xml`) and JaCoCo in `pom.xml` alike (see
[Test coverage](#test-coverage)). The model classes carry [Bean
Validation](https://beanvalidation.org/) annotations beyond those derived from
the specification (e.g. `@Digits` and `@DecimalMin` on the resources of
`TesResources`); restore them by hand in the generated code, and reconcile
any other manual changes, e.g. the check `isUrlOrContentGiven` of `TesInput` (a generator that adds
`@ScriptAssert(lang = "javascript")` instead brings back a check that fails
on Java 15 and later, which have no JavaScript engine). Until
the experimental private image credential workaround remains supported, also
restore the `docker_registry`, `docker_login` and `docker_pass` fields,
validation, write-only handling, and experimental API descriptions in
`TesExecutor`; the API and converter tests check this extension.

The generator (OpenAPI Generator 7, with `useSpringBoot3` and `useJakartaEe`)
writes Jakarta and OpenAPI 3 (`io.swagger.v3`) annotations. The committed
stubs were generated with version 6.6.0 and moved to these annotations by hand
with the move to Spring Boot 4, so the next generation also changes their
layout (e.g. constructors with the required fields; no `TesServiceInfoAllOf`
and `TesServiceTypeAllOf`). Keep the annotations that make the API description
match the API: `requiredMode = NOT_REQUIRED` on `type` of `TesInput` and
`TesOutput` (which may be left out), `accessMode = WRITE_ONLY` on
`docker_login` and `docker_pass`, and the schema name `tesResources` of
`TesResources`.
