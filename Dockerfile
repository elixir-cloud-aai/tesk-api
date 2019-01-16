FROM maven:3.3.3-jdk-8 as build

WORKDIR /
ADD . /
RUN mvn clean package

FROM openjdk:8-jre-alpine
VOLUME /tmp
COPY --from=build /target/tesk-*.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app.jar
