# Development

TESK API is a [Spring Boot](https://spring.io/projects/spring-boot) 2.7
application on Java 8, built with Maven (through the wrapper `./mvnw`). The
server stub is generated from the TES specification with the [Swagger Codegen
Maven
plugin](https://github.com/swagger-api/swagger-codegen/tree/master/modules/swagger-codegen-maven-plugin),
and the [Kubernetes Java client](https://github.com/kubernetes-client/java)
talks to the Kubernetes API.

## Building and testing

To build TESK API and run the unit tests (JUnit, Surefire, in
`src/test/java`) and the integration tests (Failsafe, in
`src/integration-test/java`; the Kubernetes API and the OpenID Connect
provider are mocked with WireMock, so no cluster is needed):

```bash
./mvnw clean verify
```

JaCoCo writes the coverage reports to `target/site/jacoco/` (unit tests) and
`target/site/jacoco-it/` (integration tests).

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
to `dev`: it checks the pull request title, lints the workflows, builds and
tests TESK API (with coverage to [Codecov](https://codecov.io/gh/elixir-cloud-aai/tesk-api)),
builds the docs strictly, starts the image in both profiles, and scans the
image for known vulnerabilities. The scan fails on critical and high
vulnerabilities that have a fix, except those accepted in `.trivyignore.yaml`,
each with a reason and an expiry date. On pushes to `dev`, it publishes the
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
`<org>/tesk-api`:

Tag | Image tags | Example
--- | --- | ---
`vX.Y.Z` | `X.Y.Z`, and `latest` if it is the highest release on `main` | `v1.2.0` publishes `1.2.0`
`vX.Y.ZrcN` (also `aN`, `bN`) | `X.Y.ZrcN` only | `v1.2.0rc1` publishes `1.2.0rc1`

The commit must be on `main`, CI must have passed for its push to `dev`, and
the versions must match the tag, so set them in a pull request first:

- `pom.xml`: `X.Y.Z`, also for the pre-releases of `X.Y.Z` (e.g., `1.2.0` for
  `v1.2.0rc1`);
- `CITATION.cff`: the tag without `v` (e.g., `1.2.0rc1` for `v1.2.0rc1`),
  with `date-released`.

`latest` goes by tags, so if a release fails, follow it with a higher version.
Only admins can create release tags, and nobody can move or delete them (tag
rulesets). Publishing waits for an approval in the `release` environment. The
image is scanned before it is tagged; images are signed with Sigstore and
carry provenance attestations.

After publishing, the workflow makes one `POST /repositories/{id}/builds` to
[Pubgrade](https://github.com/elixir-cloud-aai/Pubgrade), which notifies the
deployments that subscribed to upgrades, also for pre-releases, if they
subscribed to those. This needs Pubgrade's publish mode
([#33](https://github.com/elixir-cloud-aai/Pubgrade/issues/33)) and these
settings of the `pubgrade` environment of the repository, which should only
allow version tags and needs no reviewer; without them, the step does
nothing:

Setting | Kind | Meaning
--- | --- | ---
`PUBGRADE_URL` | Variable | Base URL of the Pubgrade API, with `https://`
`PUBGRADE_REPOSITORY_ID` | Variable | The identifier from registering the repository in Pubgrade
`PUBGRADE_TOKEN` | Secret | The access token from registering the repository in Pubgrade

## Generating a new API stub

The TES specification (v1.0) is linked as the submodule `tes`. After updating
it to a new version of the specification, run:

```bash
./mvnw clean generate-sources -P generate-swagger
```

This generates new versions of the model (`uk.ac.ebi.tsc.tesk.tes.model`) and
the API stub (`uk.ac.ebi.tsc.tesk.tes.api`). The model classes carry [Bean
Validation](https://beanvalidation.org/) annotations beyond those derived from
the specification; restore them by hand in the generated code, and reconcile
any other manual changes.
