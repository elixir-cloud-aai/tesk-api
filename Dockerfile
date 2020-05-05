FROM openjdk:8-jdk-alpine as builder
COPY . /
RUN ./mvnw clean verify

FROM openjdk:8-jdk-alpine
VOLUME /tmp
ADD target/tesk-0.0.1-SNAPSHOT.jar app.jar --from=builder
#ADD ./config config
#ENV KUBECONFIG="/config"
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app.jar
