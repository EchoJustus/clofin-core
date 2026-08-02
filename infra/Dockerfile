# CloFin service image.
#
# Two stages: a build stage that resolves dependencies and produces an
# uberjar-free classpath, and a slim runtime stage containing only a JRE,
# the application source and the resolved dependency jars.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM clojure:temurin-21-tools-deps-bookworm AS build

WORKDIR /build

# Resolve dependencies first so this layer is cached across source changes.
COPY deps.edn ./
RUN clojure -P -M:run

COPY src ./src
COPY resources ./resources

# Emit the runtime classpath and copy every dependency jar to a known location,
# so the runtime stage needs neither Maven nor the Clojure CLI.
RUN set -eux; \
    clojure -Spath -M:run > /build/classpath.txt; \
    mkdir -p /build/lib; \
    tr ':' '\n' < /build/classpath.txt \
      | grep '\.jar$' \
      | while read -r jar; do cp "$jar" /build/lib/; done

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble AS runtime

# wget backs the container healthcheck in docker-compose.yml.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends wget; \
    rm -rf /var/lib/apt/lists/*

# Run as an unprivileged user.
RUN groupadd --system --gid 1001 clofin \
 && useradd  --system --uid 1001 --gid clofin --home-dir /app --shell /usr/sbin/nologin clofin

WORKDIR /app

COPY --from=build /build/lib       /app/lib
COPY --from=build /build/src       /app/src
COPY --from=build /build/resources /app/resources

USER clofin

ENV CLOFIN_HTTP_HOST=0.0.0.0 \
    CLOFIN_HTTP_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Dfile.encoding=UTF-8"

EXPOSE 8080

# Source is loaded from /app/src at start-up; ahead-of-time compilation is
# deliberately avoided so that stack traces map to the code in this repository.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp '/app/src:/app/resources:/app/lib/*' clojure.main -m clofin.main"]
