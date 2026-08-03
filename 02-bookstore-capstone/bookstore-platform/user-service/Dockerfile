# user-service — the canonical service image. The other seven are the same file with a different
# module name, port and health URL; the reasoning is written out here and summarised in the platform
# README under Step 10a.
#
# Build from `bookstore-platform/`, not from this directory — the build needs the parent pom:
#
#   docker build -f user-service/Dockerfile -t bookstore/user-service:latest .
#
# ---------------------------------------------------------------------------------------------------
# STAGE 1 — build. A JDK, Maven, the whole dependency tree. None of it ships.
# ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Only this module's sources enter the context copy, plus the parent pom it inherits from. Copying the
# whole platform instead would be one line shorter and would mean an edit to analytics-service
# invalidates user-service's build cache — eight images rebuilt because one unrelated service changed.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY user-service/ user-service/

# `-f user-service/pom.xml` rather than `-pl user-service -am`. The aggregator lists nine modules and
# refuses to build a reactor whose directories are missing; resolving the parent by relativePath needs
# only the parent pom, which is the one file above.
#
# The cache mount keeps ~/.m2 outside the image. Two effects worth separating: the downloaded
# dependencies do not become an image layer (they are already in the jar), and a rebuild after a code
# change re-resolves nothing.
#
# Tests are skipped ON PURPOSE, and this is the one decision in the file that could be wrong. They need
# Testcontainers, which needs a Docker daemon inside the build — docker-in-docker, for a suite that
# already runs green outside. The safety this gives up is real: nothing here stops an image being built
# from code that fails its tests. Step 11 is where the pipeline makes a failing test fail the build,
# and until then "it built" means only that it compiled.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -f user-service/pom.xml -DskipTests package

# Split the fat jar into layers by rate of change. Spring Boot's own layering: dependencies (~60 MB,
# changes when a pom does), spring-boot-loader (~250 KB, changes with the Boot version), snapshot
# dependencies, then application (~100 KB, changes every commit).
#
# Copied as one jar, every push after a one-line fix re-uploads 60 MB. Copied as four layers, it
# re-uploads the 100 KB one. Same bytes in the container, different number of layers — and the layer is
# the unit Docker caches, pushes and pulls.
RUN java -Djarmode=tools -jar user-service/target/user-service-0.0.1-SNAPSHOT.jar \
    extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------------------------------
# STAGE 2 — runtime. A JRE and the application. No compiler, no Maven, no source.
# ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root. A container escape starts with whatever the process is, and a JVM serving HTTP has no
# reason to be root inside its own namespace. It also makes the filesystem effectively read-only to the
# application without needing a flag: /app belongs to root and this user cannot write to it.
RUN addgroup -S app && adduser -S -G app app
USER app

# Ordered least-changing first, so the layer that changes on every commit is the last and smallest one.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

EXPOSE 8081

# The image can say whether it is healthy; compose reads this for `depends_on: service_healthy`, and
# Kubernetes ignores it entirely and uses its own probes (10c). `wget` is busybox's, already in alpine.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- http://localhost:8081/actuator/health || exit 1

# -XX:MaxRAMPercentage=75, and it is not a tuning knob — it is a correctness fix.
#
# A modern JVM reads the cgroup limit rather than the host's RAM, so `--memory 512m` is seen. What it
# then does with it is the trap: MaxRAMPercentage defaults to 25%, so a 512 MB container runs a 128 MB
# heap and OOMs under load while three quarters of its memory sits unused. Raising it to 75% leaves
# room for the parts of a JVM that are not heap — metaspace, thread stacks, code cache, direct buffers
# — which is why this is 75 and not 90.
#
# The exec form (no shell) matters: with the shell form, PID 1 is /bin/sh and SIGTERM goes to the
# shell, which does not forward it. Kubernetes sends SIGTERM and waits 30 seconds before SIGKILL, so
# the difference is a graceful shutdown versus every in-flight request dropped, on every deploy.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
