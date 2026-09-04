# syntax=docker/dockerfile:1
#
# BUILD CONTEXT IS THE REPOSITORY ROOT, not app/:
#     docker build -f app/Dockerfile .
#
# The lockfile lives at the root because app/ is an npm workspace, and a build
# context of ./app could not see it. `npm ci` requires the lockfile, so the
# context has to include it. The root .dockerignore keeps everything else
# (infra/, gitops/, chart/, .git) out of the layer.

# ---------- build ----------
FROM node:20.18.1-bookworm-slim AS build
WORKDIR /build

# Manifests first: this layer stays cached until dependencies actually change.
COPY package.json package-lock.json ./
COPY app/package.json ./app/package.json
RUN npm ci

COPY app/tsconfig.json ./app/tsconfig.json
COPY app/src ./app/src
RUN npm run build --workspace app

# Re-resolve with production dependencies only, so nothing from
# devDependencies is carried into the runtime image.
RUN npm ci --omit=dev --workspace app --include-workspace-root

# ---------- runtime ----------
# Distroless: no shell, no package manager, no busybox. Smallest attack
# surface, and the reason anything needing `sh` (the label-sync CronJob) uses
# a different image entirely.
FROM gcr.io/distroless/nodejs20-debian12:nonroot

WORKDIR /app
# Workspace installs hoist to the root node_modules.
COPY --from=build --chown=nonroot:nonroot /build/node_modules ./node_modules
COPY --from=build --chown=nonroot:nonroot /build/app/dist ./dist

USER nonroot
EXPOSE 8080
ENV NODE_ENV=production PORT=8080

# There is no shell and no curl in this image, so the check runs through the
# node binary that is already the ENTRYPOINT (dist/healthcheck.js speaks HTTP
# directly). Kubernetes uses its own probes from the chart; this covers
# `docker run` and any non-k8s runtime.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD ["/nodejs/bin/node", "dist/healthcheck.js"]

# The distroless nodejs image's ENTRYPOINT is already `node`, so this is the
# argument to it. Exec form — PID 1 receives SIGTERM directly and the graceful
# shutdown handler in server.ts actually runs.
CMD ["dist/server.js"]
