# Meridian API — Spring Boot on Java 21.
#
# Two stages: build the jar with Maven, then run it on a JRE-only base. The runtime
# image carries no JDK, no Maven and no source, which keeps it small and gives an
# attacker materially less to work with if they ever get execution inside it.
#
# Render builds this from the repository root context with `dockerfilePath` and
# `dockerContext` pointing at apps/api — see render.yaml.

# ---- build ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies first: this layer is cached and only re-resolves when the pom changes,
# so an ordinary source edit does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# Run as a non-root user. The app never needs to write to its own filesystem.
RUN groupadd --system --gid 1001 meridian \
 && useradd --system --uid 1001 --gid meridian --home /app --shell /usr/sbin/nologin meridian

WORKDIR /app

COPY --from=build --chown=meridian:meridian /build/target/meridian-api-*.jar /app/meridian-api.jar

USER meridian

# Render injects PORT; the app reads it via server.port. 4000 is the local default.
ENV PORT=4000
EXPOSE 4000

# Container-aware heap sizing rather than a fixed -Xmx, so the same image behaves
# correctly whether it is on a 512MB free instance or something larger.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.security.egd=file:/dev/./urandom"

# Flyway runs at startup, so there is no separate migration step to orchestrate —
# the app migrates the schema and then serves. `exec` keeps the JVM as PID 1 so it
# receives SIGTERM directly and shuts down gracefully.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/meridian-api.jar"]
