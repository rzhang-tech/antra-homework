# api-gateway — the only address a client needs.
#
# Structurally the same as user-service/Dockerfile (see that file for the multi-stage, layering,
# non-root and heap-sizing reasoning). One difference, and it is the one Step 8c ended on.
#
#   docker build -f api-gateway/Dockerfile -t bookstore/api-gateway:latest .   # from bookstore-platform/

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY api-gateway/ api-gateway/

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -f api-gateway/pom.xml -DskipTests package

RUN java -Djarmode=tools -jar api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar \
    extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app app
USER app

COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

# TWO PORTS, and the split is a security boundary rather than a convenience.
#
# 8080 is the platform's public front door. 9090 serves actuator and nothing else — Step 8c moved it
# there after `curl localhost:8080/actuator/env` returned 200 with the platform's configuration in it,
# on the one component facing the internet. A GlobalFilter only runs for requests the route table
# matched, and /actuator is a different handler mapping, so the edge filter never saw it.
#
# EXPOSE is documentation; it publishes nothing. What makes the split real is that 10b maps only 8080
# to the host and 10c names only 8080 in the Service — the management port stays reachable from inside
# the pod and from `kubectl port-forward`, and from nowhere else, by construction rather than by rule.
EXPOSE 8080 9090

# Consequently the health check is on 9090. Asking 8080 for /actuator/health gets a 404: there is no
# route for it, which is exactly the property above, and a health check written against the public
# port would report this container unhealthy forever.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- http://localhost:9090/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
