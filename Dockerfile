# Builds the application from source, so that a plain `docker build .` is all
# that is needed. Java 8, as in `pom.xml`; the base images are pinned by digest
# and updated by Dependabot
FROM eclipse-temurin:8-jdk@sha256:e9255b50040337b0dec91027868dca1952ef5441bf0c8f878c5f790eaedbfea2 AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw -B -Dmaven.test.skip=true package \
    && cp target/tesk-*.jar /app.jar

FROM eclipse-temurin:8-jre@sha256:377fe8e36a4b1530d9d52020069367ef44313fa891a82ae102b1016a6a3a72f5
# The pinned base image has a vulnerable apt (CVE-2026-109518; fixed by
# 3.2.1ubuntu0.1 in the Ubuntu security updates); can go once Dependabot moves
# the base image to a build with the fix
RUN apt-get update && \
    apt-get install -y --no-install-recommends --only-upgrade apt libapt-pkg7.0 && \
    rm -rf /var/lib/apt/lists/*
# Run as non-root user; group 0 owns the home directory, so that arbitrary
# user IDs (e.g., on OpenShift, always in group 0) can use it too. The app
# writes only to `/tmp`; the Kubernetes client reads `~/.kube/config` (or
# `KUBECONFIG`) when it runs outside a cluster. The Ubuntu base image has a
# user `ubuntu` with UID 1000, which is replaced
ENV HOME=/home/tesk
RUN userdel --remove ubuntu 2> /dev/null || true && \
    useradd --uid 1000 --gid 0 --create-home --home-dir "${HOME}" tesk && \
    chmod -R g=u "${HOME}"
VOLUME /tmp
COPY --from=build /app.jar /app.jar
WORKDIR ${HOME}
USER 1000:0
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app.jar
