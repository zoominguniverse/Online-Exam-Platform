FROM maven:3.9.12-eclipse-temurin-21@sha256:c3c9d3ac4ce8431a3995c0318b8d390f448e693dd4fabc16e9b68d2e1f3d7b46 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,id=exam-java-maven,target=/root/.m2 \
    mvn -B -ntp -DskipTests package && cp -a /root/.m2/repository /opt/maven-repository

FROM eclipse-temurin:21.0.11_10-jre@sha256:c8e00005c840f4e15a98dee36caf8b7be1e385350d63f4eb4a91e465bffa98a1
WORKDIR /app
COPY --from=build /build/target/exam-platform.jar .
RUN mkdir -p /var/lib/exam-platform/attachments && chown 10001:10001 /var/lib/exam-platform/attachments
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/exam-platform.jar"]
