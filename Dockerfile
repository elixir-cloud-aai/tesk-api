# Builds the application from source, so that a plain `docker build .` is all
# that is needed. Java 21, as in `pom.xml`; the base images are pinned by digest
# and updated by Dependabot
FROM eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07 AS build
# The Maven wrapper downloads the .zip of Maven and checks its SHA-256 sum
# (`.mvn/wrapper/maven-wrapper.properties`); without `unzip` it would fetch the
# .tar.gz instead, which the sum does not match
RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw -B -Dmaven.test.skip=true package \
    && cp target/tesk-*.jar /app.jar

FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
# Run as non-root user; group 0 owns the home directory, so that arbitrary
# user IDs (e.g., on OpenShift, always in group 0) can use it too. The app
# writes only to `/tmp`; the Kubernetes client reads `~/.kube/config` (or
# `KUBECONFIG`) when it runs outside a cluster. The user `ubuntu` of the
# Ubuntu base image is removed, as it has UID 1000
ENV HOME=/home/tesk
RUN if id ubuntu > /dev/null 2>&1; then userdel --remove ubuntu; fi && \
    useradd --uid 1000 --gid 0 --create-home --home-dir "${HOME}" tesk && \
    chmod -R g=u "${HOME}"
VOLUME /tmp
COPY --from=build /app.jar /app.jar
WORKDIR ${HOME}
USER 1000:0
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app.jar
