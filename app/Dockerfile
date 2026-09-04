# syntax=docker/dockerfile:1

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependency manifest first: this layer only busts when the pom changes, so
# source edits reuse the resolved dependency cache.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- runtime ----------
#
# BASE IMAGE CHOICE — this is deliberate, do not "optimise" it back to distroless.
#
# gcr.io/distroless/java21-debian12 carries the SAME Debian 12 package set that
# blocked this project's previous image on three HIGH OpenSSL CVEs
# (CVE-2026-28389, CVE-2026-28390, CVE-2026-45447 in libssl3, fixed in
# 3.0.20-1~deb12u2). Distroless has no package manager, so those cannot be
# patched from a Dockerfile — the only options there are to wait for Google to
# rebuild, or to suppress the finding.
#
# Temurin on Ubuntu Jammy is a different package universe AND has apt, so the
# upgrade below actually fixes them at build time. The cost is a shell in the
# image; that is mitigated with a non-root user and a read-only root filesystem
# enforced by the chart's securityContext.
FROM eclipse-temurin:21-jre-jammy AS runtime

# Patch OS packages, then drop the apt lists so the layer stays small.
# --no-install-recommends is irrelevant here (no new packages), but the
# lists removal is what keeps this from doubling the image size.
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# UID 65532 matches the chart's podSecurityContext.runAsUser, so the filesystem
# ownership below and the pod's effective user agree.
RUN groupadd --gid 65532 app \
    && useradd --uid 65532 --gid 65532 --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=65532:65532 /build/target/app.jar /app/app.jar

USER 65532:65532
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

# The chart mounts a read-only root filesystem, so Tomcat's work directory is
# pointed at /tmp (see application.properties).
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /healthz HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q '200'"]

# Exec form: the JVM is PID 1 and receives SIGTERM directly, so the graceful
# shutdown configured in application.properties actually runs.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
