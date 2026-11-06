# Upgrading

## To 1.2.0

From 1.1.0, nothing in your configuration has to change. What's new:

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
