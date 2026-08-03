# book-service — the catalog, and the only service that talks to S3 and DynamoDB.
#
# Structurally identical to user-service/Dockerfile, which carries the full reasoning: multi-stage so
# that the JDK and Maven do not ship, layered so that a one-line change re-pushes 262 kB instead of
# 74 MB, non-root, and -XX:MaxRAMPercentage=75 because the JVM's 25% default would give a 512 MB
# container a 128 MB heap.
#
# Eight near-identical files is real duplication, and a single parameterised Dockerfile taking
# `ARG SERVICE` would remove it. It is deliberately not used, for the same reason a shared jar is not
# (D12): the shared file becomes something all eight services must agree on. api-gateway already needs
# a second port and a different health URL; book-service would need extra packages the day it does
# image work in-process. **A service owns its own build**, and a build file that changes for one
# service must not be a file the other seven depend on.
#
#   docker build -f book-service/Dockerfile -t bookstore/book-service:latest .   # from bookstore-platform/

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY book-service/ book-service/

RUN --mount=type=cache,target=/root/.m2     ./mvnw -B -q -f book-service/pom.xml -DskipTests package

RUN java -Djarmode=tools -jar book-service/target/book-service-0.0.1-SNAPSHOT.jar     extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app app
USER app

COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

EXPOSE 8082

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5     CMD wget -qO- http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
