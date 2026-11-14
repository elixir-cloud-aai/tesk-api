# Deployment

## Image

Releases are published to Docker Hub as `elixircloud/tesk-api:X.Y.Z`, and the
highest release also as `latest`. Each push to the `dev` branch publishes
`elixircloud/tesk-api:dev` and `elixircloud/tesk-api:sha-<commit>`, for
testing. All images are scanned for known vulnerabilities before they are
tagged, signed with [Sigstore](https://www.sigstore.dev/) (keyless) and carry
SBOM and build provenance attestations.

To check that a release image was signed by the release workflow for a
version tag, with
[cosign](https://docs.sigstore.dev/cosign/system_config/installation/):

```bash
cosign verify elixircloud/tesk-api:1.2.0 \
  --certificate-identity-regexp '^https://github\.com/elixir-cloud-aai/tesk-api/\.github/workflows/release\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

For `dev` images, the identity is
`https://github.com/elixir-cloud-aai/tesk-api/.github/workflows/ci.yml@refs/heads/dev`.

The image runs the application with Java 21 on [Eclipse
Temurin](https://adoptium.net/) and listens on port 8080. `JAVA_OPTS` passes
options to the JVM. It runs as the non-root user `tesk` (UID 1000, group 0,
home directory `/home/tesk`); any other UID in group 0 works too, as on
OpenShift, which assigns one per project. The app writes only to `/tmp`, so
the root file system can be read-only if `/tmp` is a writable volume. Outside
a cluster, it reads the kubeconfig file from `KUBECONFIG` or
`/home/tesk/.kube/config`; `--user "$(id -u):0"` in the example below lets it
read a kubeconfig that only your user may read. With rootless Docker, where
your files belong to UID 0 in the container, use `--user 0:0` instead.

## Kubernetes

TESK API runs in the Kubernetes cluster whose jobs it manages, deployed with
the Helm chart of [TESK](https://github.com/elixir-cloud-aai/TESK), which also
deploys the taskmaster's settings, the service account and the ingress. See the
TESK documentation for the installation; the chart sets the [settings of TESK
API](configuration.md) from its values.

TESK API talks to the Kubernetes API with the [Kubernetes Java
client](https://github.com/kubernetes-client/java). Inside the cluster, it uses
the service account of its pod (`spec.template.spec.serviceAccountName` of its
deployment); outside, the kubeconfig file. Either way, it needs these rights in
its namespace (`TESK_API_K8S_NAMESPACE`); the built-in `edit` role has them:

Resource | Verbs | For
--- | --- | ---
`jobs` (`batch`) | `create`, `delete`, `get`, `list`, `patch` | Taskmaster jobs: create, read, list, label as cancelled (and end one whose taskmaster doesn't run yet); delete one whose ConfigMap couldn't be created
`pods` | `list`, `patch` | Pods of the jobs: read, label as cancelled
`pods/log` | `get` | Logs of the taskmaster and executors
`configmaps` | `create`, `delete`, `get` | The taskmaster's input, owned by its job (`delete` where the admission plugin `OwnerReferencesPermissionEnforcement` is on, e.g. on OpenShift: it lets only those who may delete an object set its owner); a pod security context from a ConfigMap, if configured
`secrets` | `create`, `delete` | Private executor image credentials, as Secrets owned by the taskmaster Job (`delete` for `OwnerReferencesPermissionEnforcement`)
`limitranges` | `list` | Raising requested memory to the namespace's minimum (without the right, it logs an error and goes on)

Passing private executor image credentials in TES requests is an
[experimental workaround for proof-of-concept use only](api.md#private-executor-images),
not a feature for production clients.

The taskmaster's service account (`TESK_API_TASKMASTER_SERVICE_ACCOUNT_NAME`)
needs to manage jobs, pods and persistent volume claims, and to read pod
logs. That includes `patch` on `jobs`, with which a taskmaster of
elixir-cloud-aai/tesk-core
[#144](https://github.com/elixir-cloud-aai/tesk-core/issues/144) or later
stores the [output file logs](api.md#output-file-logs), and `get` on
`pods/log`, from which it reads them; TESK's Helm chart grants both.

## Running locally

To run TESK API outside of Kubernetes, e.g. against a local cluster such as
[kind](https://kind.sigs.k8s.io/) or [k3d](https://k3d.io/), with Java 21 and
a `KUBECONFIG` file for that cluster:

```bash
./mvnw spring-boot:run
```

or with the image, reachable from your machine only (it runs without
authentication by default, with the rights of your kubeconfig, `KUBECONFIG` or
`~/.kube/config`):

```bash
docker run --rm --publish 127.0.0.1:8080:8080 --user "$(id -u):0" \
  --volume "${KUBECONFIG:-${HOME}/.kube/config}:/home/tesk/.kube/config:ro" \
  elixircloud/tesk-api:latest
```

Then try `curl http://localhost:8080/ga4gh/tes/v1/service-info`. Without a
cluster, the API still starts and answers `GET /service-info`; requests for
tasks fail. For the Swagger UI at <http://localhost:8080>, add `--env
TESK_API_SWAGGER_UI_ENABLED=true` (see [API](api.md#swagger-ui)).

## Access control

By default (`noauth` profile), TESK API needs no token, and every user sees
all tasks. For deployments that others can reach, switch on the `auth`
profile; see [Access control](access-control.md).
