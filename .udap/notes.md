# gitops-monorepo — working notes

## What this is
A GitOps **monorepo** on EKS. One repo holds app code, its Helm chart, all cluster config
(ArgoCD app-of-apps, dashboards, scrapes, label-sync) and the CI/CD spec.
Built to the user's written specification — their Sections 2/3/4/6 are the contract.

## THE FOUR INVARIANTS (user spec §4) — do not "simplify" any of these
1. **ArgoCD watches ONLY `gitops/`.** Never `app/`, `chart/`, `.github/`.
2. **Image tags are ArgoCD Helm parameters, NOT committed chart values.** The deploy patches
   `spec.source.helm.parameters` on the live Application. A deploy changes *cluster state,
   not Git*.
3. **CI builds only from `app/` + `chart/`.** `gitops/**` is excluded — GitOps changes are
   applied by the GitOps engine, not CI.
4. **`selfHeal: true` is therefore safe** — the only thing Argo manages (`gitops/`) is never
   written by the pipeline. Nothing to fight.

Break #2 and you reintroduce the CI→Argo→CI loop this design exists to prevent.

### All four are mechanically enforced (not just documented)
The `security` stage fails the build if:
- a committed `image.tag` appears in `gitops/apps` (rule 2)
- any Application points at `app/` or `.github/` (rule 1)
- a plain Deployment renders alongside a Rollout (trap 5)
- canary + blueGreen are both set

The `verify` stage re-checks `Synced` 60s AFTER the patch — proof selfHeal didn't revert it
(rule 4). All four ran green in the test_project rehearsal.

## Rollback semantics (differs from the usual GitOps story)
Because nothing is committed on deploy, rollback is **not** a git revert.
`configure` captures the previous tag before patching; the gated `rollback` stage runs
`kubectl argo rollouts undo`. Cluster state only.

## Deviations from the user's spec, and why
| Spec | Here | Reason |
|---|---|---|
| §2.1–2.3 hand-run `helm install` / `kubectl apply` | Terraform `helm_release` ×3 + `kubectl_manifest` | Greenfield; platform refuses deploy commands on the console. Same end state, reproducible. |
| §2.3 root app via `kubectl apply` | `kubectl_manifest`, **not** `kubernetes_manifest` | The latter validates CRD schemas at PLAN time → fails before the CRD exists on first apply. |
| §2.0 shared ingress/TLS/secret store "already present" | I provision ingress-nginx + NLB | Fresh cluster — they don't exist. |
| §2.6 Flux optional | Scaffolded (`flux/README.md`), NOT installed | User's own §6.4: ArgoCD-only is simpler to keep loop-free. Additive later. |
| `on: paths` trigger filter | `detect-changes` stage | Rendered workflows are workflow_dispatch-only; can't express `on: paths`. Same discipline. |
| plain YAML in `gitops/apps/` | a Helm **chart** | repo URL + ECR URL aren't knowable until the cluster exists. Terraform passes them as params on the root app. The alternative (pipeline patches them later) would write into `gitops/` and break rule 4. |

## Five traps, designed in (user §6.3)
1. Tag overwrite → immutable `git-<sha>`; ECR repo is `IMMUTABLE`; build refuses an existing
   tag; `imagePullPolicy: Always`.
2. ArgoCD ships no controller-metrics Service → added Service + ServiceMonitor; verify checks both.
3. Stale console label → every-minute label-sync CronJob.
4. Distroless has no shell → CronJob uses `bitnami/kubectl`. **App image stays distroless**
   (its Docker HEALTHCHECK is a node script, since there's no curl).
5. Plain-Deployment guard → renders only when both mode switches are false; enforced in CI.

## Infra facts
- Account 241533126054, us-east-1. Quotas: 64 vCPU, 5 EIPs, 5 VPCs.
- Dedicated VPC (default VPC has no private subnets). 2 AZs, **single NAT gateway** —
  saves ~£30/mo, costs AZ-independent egress.
- EKS **1.33** (standard support). Node group 3× t3.large (t3.medium cannot hold the platform).
- EBS CSI addon + gp3 default StorageClass — without them Prometheus/Grafana PVCs sit Pending
  forever and the observability Application never goes Healthy.
- `cluster_name` is a terraform output; configure/verify/rollback each re-init and read it
  themselves (self-sufficient job rule).

## Layout gotcha
`app/` is an **npm workspace**; the single lockfile is at the REPO ROOT. Therefore the Docker
build context is the root, not `./app`:  `docker build -f app/Dockerfile .`
Do not "tidy" this back to a context of ./app — `npm ci` would lose the lockfile.

## Status
- [x] meta approved, cloud probed, architecture + pipeline (rev 5) written, design + plan approved
- [x] generated infra/ app/ chart/ gitops/ + README + flux/README
- [x] validate_project PASS (50 files); test_project PASSED (lint, typecheck, tests, all 4 guards)
- [ ] create_repo_and_push
- [ ] set secrets → deploy

## Secrets to set AFTER first push, BEFORE deploy
- `GITOPS_REPO_URL` — https URL of this repo (every Application's repoURL, via TF_VAR_repo_url)
Note: no ARGOCD_ADMIN_PASSWORD needed — the chart generates
`argocd-initial-admin-secret` in-cluster; nothing to commit or pass in.
