# Deployment

## Image

Releases are published to Docker Hub as `elixircloud/tesk-api:X.Y.Z`, and the
highest release also as `latest`. Each push to the `dev` branch publishes
`elixircloud/tesk-api:dev` and `elixircloud/tesk-api:sha-<commit>`, for
testing. All images are scanned for known vulnerabilities before they are
tagged, signed with [Sigstore](https://www.sigstore.dev/) (keyless) and carry
SBOM and build provenance attestations.

To check the signature of an image, with
[cosign](https://docs.sigstore.dev/cosign/system_config/installation/):

```bash
cosign verify elixircloud/tesk-api:1.2.0 \
  --certificate-identity-regexp '^https://github\.com/elixir-cloud-aai/tesk-api/\.github/workflows/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

The image runs the application with Java 8 on [Eclipse
Temurin](https://adoptium.net/) and listens on port 8080. `JAVA_OPTS` passes
options to the JVM.

## Kubernetes

TESK API runs in the Kubernetes cluster whose jobs it manages, deployed with
the Helm chart of [TESK](https://github.com/elixir-cloud-aai/TESK), which also
deploys the taskmaster's settings, the service account and the ingress. See the
TESK documentation for the installation; the chart sets the [settings of TESK
API](configuration.md) from its values.

TESK API talks to the Kubernetes API with the [Kubernetes Java
client](https://github.com/kubernetes-client/java). Inside the cluster, it uses
the service account of its pod (`spec.template.spec.serviceAccountName` of its
deployment); outside, the `KUBECONFIG` file. Either way, it needs the rights to
create, read, list and label jobs and pods and to read pod logs in its
namespace (`TESK_API_K8S_NAMESPACE`); the built-in `edit` role is enough. The
taskmaster's service account (`TESK_API_TASKMASTER_SERVICE_ACCOUNT_NAME`)
needs to manage jobs, pods and persistent volume claims.

## Running locally

To run TESK API outside of Kubernetes, e.g. against a local cluster such as
[kind](https://kind.sigs.k8s.io/) or [k3d](https://k3d.io/), with Java 8 and
a `KUBECONFIG` file for that cluster:

```bash
./mvnw spring-boot:run
```

or with the image:

```bash
docker run --rm --publish 8080:8080 \
  --volume "${HOME}/.kube/config:/root/.kube/config:ro" \
  elixircloud/tesk-api:latest
```

Then open the Swagger UI at <http://localhost:8080>. Without a cluster, the
API still starts and answers `GET /service-info`; requests for tasks fail.

## Access control

By default (`noauth` profile), TESK API needs no token, and every user sees
all tasks. For deployments that others can reach, switch on the `auth`
profile; see [Access control](access-control.md).
