# Upgrading

## To the next release

Changes since 1.2.0 that clients or deployments may notice:

- **Task cancellation response.** `POST /tasks/{id}:cancel` answers a
  successful cancellation with an empty JSON object (`{}`, `Content-Type:
  application/json`), as the TES specification defines, instead of an empty
  body. Clients that checked for an empty body need to accept `{}`.
- **Non-root image.** The image runs as the user `tesk` (UID 1000, group 0)
  instead of `root`, with the home directory `/home/tesk` (see
  [Deployment](deployment.md#image)). The Helm chart of TESK already runs it
  as UID 1000 when its security context is enabled (`tesk.securityContext`,
  the default). If you run the image yourself with a kubeconfig file, mount
  it to `/home/tesk/.kube/config` instead of `/root/.kube/config` (or set
  `KUBECONFIG`), and run it with your own UID in group 0 (`--user "$(id
  -u):0"`) if only your user may read the file; if you mount other files,
  make sure that the container's user or group 0 can read them.

## To 1.2.0

From 1.1.0, only deployments that use the Swagger UI have to change their
configuration. What's new:

- **Swagger UI off by default.** TESK API no longer serves the Swagger UI and
  its static resources and configuration (`/swagger-resources`) unless
  `TESK_API_SWAGGER_UI_ENABLED` is `true`; the root path (`/`) then redirects
  to it as before. While it is off, these paths and `/` answer `404 Not
  Found`. The API description (`/v2/api-docs`) is served either way. If you or
  your users rely on the Swagger UI, e.g. for its built-in OpenID Connect
  client, set the variable in your deployment (see [API](api.md#swagger-ui));
  the Helm chart of TESK has no value for it yet. Turning it on serves the
  Swagger UI and its static resources publicly, also with the `auth` profile:
  turn it on only where it is needed.
- **Service info setting.** `TESK_API_SERVICE_INFO_PUBLIC` (`true` by
  default) keeps `GET` and `HEAD /service-info` public with the `auth`
  profile, as before; set it to `false` to require a valid token for them (see
  [Configuration](configuration.md#service-info)). The Helm chart of TESK sets
  it with `auth.service_info_public`, which needs TESK API 1.2.0 or later.
- **Image.** The image is built from source on Eclipse Temurin 8 (Ubuntu)
  instead of the discontinued `openjdk:8-jdk-alpine`. Releases are published
  as `elixircloud/tesk-api:X.Y.Z` (and `latest`), signed with Sigstore and
  scanned before they are published; see [Deployment](deployment.md#image).
  If you mount files into the container or run it with a fixed user ID, check
  that these still fit the new base image.
- **Service info version.** The built-in `service-info.yaml` reports version
  `1.2.0`. If you use your own file (`TESK_API_SERVICE_INFO_LOCATION`), update
  its `version` yourself.

Pre-releases (e.g. `1.2.0rc1`) are published under their own tag only and
never as `latest`.
