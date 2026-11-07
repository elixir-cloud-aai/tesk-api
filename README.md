<!-- markdownlint-disable MD033 MD041 -- logo first; <picture> for dark mode -->
<a href="https://elixir-on.cloud/">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/logo_elixir_on_cloud_night.png">
    <img src="docs/images/logo_elixir_on_cloud.png" alt="ELIXIR On-Cloud">
  </picture>
</a>
<!-- markdownlint-enable MD033 MD041 -->

# TESK API

[![License][badge-license]][badge-url-license]
[![Build_status][badge-build-status]][badge-url-build-status]
[![Docs][badge-docs]][badge-url-docs]
[![Coverage][badge-coverage]][badge-url-coverage]
[![GitHub_tag][badge-github-tag]][badge-url-github-tag]
[![Docker_image][badge-docker]][badge-url-docker]

The API server of [TESK][tesk], an implementation of the [GA4GH Task
Execution Service (TES) API][tes] for [Kubernetes](https://kubernetes.io),
brought to you by [ELIXIR On-Cloud](https://elixir-on.cloud/). TESK API
accepts TES tasks, runs each of them as Kubernetes jobs (a taskmaster, which
runs the task's executors and the filers for its inputs and outputs), and
reports their state and logs from those jobs and their pods; it needs no
database. Access can be limited to users with tokens from an OpenID Connect
provider and by group membership. It is a Spring Boot application, published
as the image `elixircloud/tesk-api` and deployed with the Helm chart of
[TESK][tesk].

**Read the [documentation][docs]** (the "TESK API" section of the TESK
documentation) for the API, the configuration, access control, deployment,
upgrades and development. Its sources are in [`docs/`](docs/).

## Quick start

With [Docker](https://docs.docker.com/get-docker/) installed, and a
Kubernetes cluster in your kubeconfig file (`KUBECONFIG`, or
`~/.kube/config`; e.g., a local one with [kind](https://kind.sigs.k8s.io/)),
start TESK API on your machine only (it runs without authentication by
default, with the rights of your kubeconfig):

```bash
docker run --rm --publish 127.0.0.1:8080:8080 \
  --volume "${KUBECONFIG:-${HOME}/.kube/config}:/home/tesk/.kube/config:ro" \
  elixircloud/tesk-api:latest
```

The API is then served at `http://localhost:8080/ga4gh/tes/v1/` (add `--env
TESK_API_SWAGGER_UI_ENABLED=true` for a Swagger UI at
[http://localhost:8080](http://localhost:8080)):

```bash
curl http://localhost:8080/ga4gh/tes/v1/service-info
```

To deploy TESK to a cluster, use the Helm chart of [TESK][tesk].

## Development

TESK API builds and runs on Java 8. To build it and run the unit and
integration tests (no cluster needed):

```bash
./mvnw clean verify
```

See [Development][docs-development] in the documentation for the image, CI,
the docs build and releases.

## Citation

If you use TESK API, please cite it with the metadata in
[`CITATION.cff`](CITATION.cff): on GitHub, "Cite this repository" in the
sidebar of the repository formats it as APA or BibTeX.

## Contributing

Contributions are welcome! See the organisation's [contributing
guide][contributing], and mind our [code of conduct][code-of-conduct].

[badge-build-status]: <https://github.com/elixir-cloud-aai/tesk-api/actions/workflows/ci.yml/badge.svg?branch=dev>
[badge-coverage]: <https://codecov.io/gh/elixir-cloud-aai/tesk-api/branch/dev/graph/badge.svg?branch=dev>
[badge-docker]: <https://img.shields.io/docker/v/elixircloud/tesk-api?sort=semver&label=image&color=C39BD3>
[badge-docs]: <https://img.shields.io/readthedocs/tesk>
[badge-github-tag]: <https://img.shields.io/github/v/tag/elixir-cloud-aai/tesk-api?color=C39BD3>
[badge-license]: <https://img.shields.io/badge/license-Apache%202.0-blue.svg>
[badge-url-build-status]: <https://github.com/elixir-cloud-aai/tesk-api/actions/workflows/ci.yml?query=branch%3Adev>
[badge-url-coverage]: <https://codecov.io/gh/elixir-cloud-aai/tesk-api?branch=dev>
[badge-url-docker]: <https://hub.docker.com/r/elixircloud/tesk-api/tags>
[badge-url-docs]: <https://tesk.readthedocs.io/en/latest/tesk-api/>
[badge-url-github-tag]: <https://github.com/elixir-cloud-aai/tesk-api/tags>
[badge-url-license]: <https://www.apache.org/licenses/LICENSE-2.0>
[code-of-conduct]: <https://github.com/elixir-cloud-aai/.github/blob/main/CODE_OF_CONDUCT.md>
[contributing]: <https://github.com/elixir-cloud-aai/.github/blob/main/CONTRIBUTING.md>
[docs]: <https://tesk.readthedocs.io/en/latest/tesk-api/>
[docs-development]: <https://tesk.readthedocs.io/en/latest/tesk-api/development/>
[tes]: <https://github.com/ga4gh/task-execution-schemas>
[tesk]: <https://github.com/elixir-cloud-aai/TESK>
