# gitops-monorepo

A GitOps delivery platform where **application code, Helm chart, cluster
configuration and CI/CD all live in one repository** — with the loop risks that
normally creates designed out rather than documented away.

Spring Boot 3 / Java 21 on EKS, delivered by ArgoCD with Argo Rollouts doing
canary and blue/green.

---

## The four invariants

This design has one genuinely hard problem: ArgoCD watches the same repository
the pipeline commits to. Get that wrong and you get a loop, or `selfHeal`
reverting your deploys. Four rules prevent it, and **all four are enforced by
CI, not by convention.**

| # | Rule | Enforced by |
|---|------|-------------|
| 1 | ArgoCD watches **only** `gitops/` — never `app/`, `chart/` or `.github/` | `security` stage fails if any Application's `path:` is under `app/` or `.github/` |
| 2 | Image tags are **ArgoCD Helm parameters**, never committed chart values | `security` stage fails on a committed `image.tag` in `gitops/apps` |
| 3 | CI builds only from `app/` + `chart/` | `detect-changes` classifies paths; `gitops/**`-only changes build nothing |
| 4 | `selfHeal: true` is therefore safe | `verify` re-checks `Synced` **60s after** the deploy patch |

Rule 2 is the load-bearing one. A deploy changes **cluster state, not Git**:
the pipeline patches `spec.source.helm.parameters` on the live Application.
There is nothing in Git for ArgoCD to fight.

## Layout

```
app/                  Spring Boot 3 service (Maven, self-contained)
chart/app/            one chart, three release modes
gitops/               everything ArgoCD manages
  root-app.yaml         the single bootstrap object
  apps/                 app-of-apps: one Application per component
  observability/        kube-prometheus-stack values
  dashboards/           Grafana dashboards as ConfigMaps
  scrapes/              ServiceScrapes (incl. ArgoCD's own metrics)
  label-sync/           CronJob keeping the console's image label honest
flux/                 scaffolded, NOT installed (see below)
infra/                Terraform: VPC, EKS, ECR, and the one bootstrap step
.udap/                architecture source of truth + pipeline spec
```

## How a deploy actually works

```
push to app/ or chart/
  └─ detect-changes → lint → test → security
       └─ build-push   build image, scan it, THEN push (never a vulnerable image)
            └─ provision   terraform apply
                 └─ configure   capture previous tag
                                patch Application's image.tag parameter
                                hard-refresh ArgoCD
                      └─ verify   Synced + Healthy
                                  running image == the tag this run built
                                  ArgoCD metrics scrapeable
                                  STILL Synced 60s later  ← rule 4
                                  /api/info reports the new tag through the ingress
                           └─ rollback (manual approval)
```

### Rollback

Not a `git revert` — nothing was committed, so there is nothing to revert.
`configure` captures the previous tag before patching; the gated `rollback`
stage rewinds the Rollout to the previous ReplicaSet's image. `gitops/` is
untouched, so `selfHeal` stays quiet throughout.

## The application

Deliberately small — this repository is about the delivery platform, not the
app. It exists to make a rollout *visible*.

| Endpoint | Purpose |
|---|---|
| `GET /` | Landing page showing the live version, release mode and pod |
| `GET /api/info` | The same three facts as JSON — what `verify` asserts against |
| `GET /healthz` | Actuator health — what the chart's probes hit |
| `GET /metrics` | Prometheus — what the ServiceScrapes read |

`/healthz` and `/metrics` are **Actuator remapped**, not the `/actuator/**`
defaults. That keeps the chart, the scrapes and the verify stage identical to
what they were before the runtime changed. A `security`-stage guard fails the
build if that mapping is removed.

`APP_VERSION`, `RELEASE_MODE` and `POD_NAME` are injected by the chart, so the
page is direct evidence of which deploy is serving you — during a canary you
can watch the version flip on refresh.

## Release modes

One chart, three modes, mutually exclusive:

```bash
helm template chart/app --set canary.enabled=true       # canary Rollout
helm template chart/app --set blueGreen.enabled=true    # blue/green Rollout
helm template chart/app                                 # plain Deployment
```

Setting both **aborts the render** rather than producing two Rollouts with the
same name. The plain Deployment renders only when both are off — otherwise two
controllers would own the same selector and the canary would never converge.

## Five traps this design handles

1. **Silent registry tag overwrite** — immutable `git-<sha>` tags, ECR set to
   `IMMUTABLE`, the build refuses a tag that already exists, and
   `imagePullPolicy: Always` as a second line of defence.
2. **ArgoCD ships no controller-metrics Service** — one is added, with a
   ServiceMonitor, so the delivery system itself is observable.
3. **Stale console image label** — an every-minute CronJob reconciles it.
4. **Distroless has no shell** — the label-sync CronJob uses `bitnami/kubectl`.
5. **Plain-Deployment guard** — renders only when both mode switches are false.

## Base image

`eclipse-temurin:21-jre-jammy`, **not** distroless, and that is deliberate.

`gcr.io/distroless/java21-debian12` carries the same Debian 12 package set that
blocked an earlier image on three HIGH OpenSSL CVEs — and distroless has no
package manager, so they cannot be patched from a Dockerfile. Jammy lets
`apt-get upgrade` fix them at build time. The cost is a shell in the image,
mitigated by a non-root user (UID 65532) and a read-only root filesystem.

The image is scanned **before** it is pushed, at HIGH/CRITICAL, so a vulnerable
image never reaches the registry.

## Flux

Scaffolded under `flux/`, **not installed**. ArgoCD's "patch the object" model
keeps deploys out of Git entirely; Flux's "commit the tag to Git" model is the
one case needing a `[skip ci]` guard. Running one engine is simpler to keep
loop-free.

The trigger exclusions and `[skip ci]` discipline are already correct, so
enabling Flux later is additive — see `flux/README.md`.

## Local development

```bash
cd app
mvn spring-boot:run          # http://localhost:8080
mvn -B verify                # the same command CI runs
```

## Cost

Roughly **£200–280/month**: EKS control plane (~£58 regardless of load), three
`t3.large` nodes, one NAT gateway, an NLB, and EBS volumes for Prometheus and
Grafana.

A single NAT gateway is a deliberate Tier-appropriate trade: it saves ~£30/mo
versus one per AZ, at the cost of egress depending on one AZ.
