# Development

TESK API is a [Spring Boot](https://spring.io/projects/spring-boot) 2.7
application on Java 21, built with Maven 3.9 (through the wrapper `./mvnw`,
which downloads the version pinned in `.mvn/wrapper/maven-wrapper.properties`). The server stub is generated from the TES
specification with the [Swagger Codegen Maven
plugin](https://github.com/swagger-api/swagger-codegen/tree/master/modules/swagger-codegen-maven-plugin),
and the [Kubernetes Java client](https://github.com/kubernetes-client/java)
talks to the Kubernetes API.

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

JaCoCo writes the coverage reports to `target/site/jacoco/` (unit tests) and
`target/site/jacoco-it/` (integration tests). The build fails if the unit and
integration tests together cover less than 60% of the lines or 45% of the
branches (`check` of `jacoco-maven-plugin` in `pom.xml`; raise the floors as
coverage grows). Codecov (`codecov.yml`) also checks each pull request: the
project's coverage must not drop by more than 0.5 percentage points, and the
changed lines must be covered completely.

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

These two commands need Java 8 (e.g. `JAVA_HOME` pointing to a Java 8 JDK),
as the CI job `Lint` runs them: google-java-format 1.7 (with
spotless-maven-plugin 2.30.0) doesn't run on Java 21, and the newer versions
that do format the code differently. The update, with the reformat it brings,
is part of the move to Spring Boot 3
([#66](https://github.com/elixir-cloud-aai/tesk-api/issues/66)). Building and
testing need Java 21.

The commits that only reformatted the code are listed in
`.git-blame-ignore-revs`. GitHub skips them in its blame view; for `git blame`,
run once:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

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
workflow makes one `POST /repositories/{id}/builds` to
[Pubgrade](https://github.com/elixir-cloud-aai/Pubgrade), which notifies the
deployments that subscribed to upgrades, also for pre-releases, if they
subscribed to those. This needs Pubgrade's publish mode
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
if one of the other settings is missing. If only this job fails or is
rejected, the image is published all the same: fix the settings and re-run
the job, or leave it; don't release a new version for it. Settings of the
former `pubgrade` environment are no longer read: move them as above.

## Generating a new API stub

The TES specification (v1.0) is linked as the submodule `tes`. After updating
it to a new version of the specification, run:

```bash
./mvnw clean generate-sources -P generate-swagger
```

This generates new versions of the model (`uk.ac.ebi.tsc.tesk.tes.model`) and
the API stub (`uk.ac.ebi.tsc.tesk.tes.api`). The model classes carry [Bean
Validation](https://beanvalidation.org/) annotations beyond those derived from
the specification (e.g. `@Digits` and `@DecimalMin` on the resources of
`TesResources`); restore them by hand in the generated code, and reconcile
any other manual changes, e.g. the internal field `userIdFromLegacyLabel` of
`TesTaskLog` (not serialized), which the access check of a task needs. Until
the experimental private image credential workaround remains supported, also
restore the `docker_registry`, `docker_login` and `docker_pass` fields,
validation, write-only handling, and experimental API descriptions in
`TesExecutor`; the API and converter tests check this extension.
