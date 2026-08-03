# config-server — the one every other service refuses to start without.
#
# Structurally the same as user-service/Dockerfile (see that file for the multi-stage, layering,
# non-root and heap-sizing reasoning). Two things are genuinely different, and both are about the fact
# that this service's payload is not code.
#
#   docker build -f config-server/Dockerfile -t bookstore/config-server:latest .   # from bookstore-platform/

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY config-server/ config-server/

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -f config-server/pom.xml -DskipTests package

RUN java -Djarmode=tools -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar \
    extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app app

# DIFFERENCE 1 — the configuration itself is copied in, and that is a development-only shortcut.
#
# The `native` profile serves files from a directory, and inside a container there is no working
# directory two levels up to find them in. Baking them in makes the image self-contained and makes
# `docker compose up` work with nothing mounted.
#
# What it costs is precisely what a config server is for: an image is immutable, so changing a value
# now means a rebuild and a redeploy of the config server — the property that Step 6 spent its time
# removing. That is why the `git` profile exists and is what a real deployment runs:
#
#   docker run -e SPRING_PROFILES_ACTIVE=git -e CONFIG_REPO_URI=https://... bookstore/config-server
#
# with nothing baked in and the repository as the source of truth. Both profiles are in the image; the
# default is the one that suits a laptop.
COPY config-repo/ /config-repo/
ENV CONFIG_REPO=file:/config-repo

USER app

COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

EXPOSE 8888

# DIFFERENCE 2 — nothing here supplies ENCRYPT_KEY, and nothing in this repository ever will.
#
# The master key that decrypts every `{cipher}` value in /config-repo is passed at run time, by
# compose (10b) or by a Kubernetes Secret (10c). Baked into the image it would sit in a layer, in a
# registry, readable by anyone who can pull the tag — a master key shipped alongside the ciphertext it
# protects, which is the one thing config-server/application.yml says not to do.
#
# Start it without the key and the failure is loud in the right place: the server serves
# `invalid.app.jwt.secret` and every client refuses to start.

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -qO- http://localhost:8888/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
