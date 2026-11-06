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
VOLUME /tmp
COPY --from=build /app.jar /app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app.jar
