# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build

WORKDIR /workspace/vehicle-runtime-service
COPY vehicle-runtime-service/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests dependency:go-offline
COPY vehicle-runtime-service/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM eclipse-temurin:21-jre@sha256:d2b9f8f12212cadcfdf889461531784e8fd097feade954d65b31ee7a71c473ec

WORKDIR /workspace
COPY --from=build /workspace/vehicle-runtime-service/target/vehicle-runtime-service-0.1.0-SNAPSHOT.jar /workspace/vehicle-runtime-service.jar

EXPOSE 9300
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/workspace/vehicle-runtime-service.jar"]
