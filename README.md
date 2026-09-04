# gitops-monorepo

One repository holding the application, its Helm chart, all cluster configuration, and the
CI/CD pipeline. ArgoCD reconciles the cluster to Git; the pipeline builds and feeds versions
in. Gradual releases via Argo Rollouts, one-click rollback, everything watched on Grafana.

```
app/                      application source + Dockerfile        <- CI builds this
chart/app/                the Helm chart (3 release modes)       <- CI builds this
gitops/
  root-app.yaml           the single bootstrap Application
  apps/                   app-of-apps: one Application per component
  observability/          kube-prometheus-stack values
  dashboards/             Grafana dashboards as ConfigMaps
  scrapes/                ServiceMonitors (incl. ArgoCD's own)
  label-sync/             the console label-accuracy CronJob
infra/                    Terraform: VPC, EKS, ECR, and the one bootstrap step
.udap/pipeline.yaml       pipeline spec -> renders .github/workflows/
```

## The four rules that keep a monorepo from eating itself

The risk of putting CI and GitOps in one repo is that they fight: the pipeline commits,
ArgoCD reconciles, `selfHeal` reverts, something retriggers. Four rules remove it.

**1. ArgoCD watches only `gitops/`.** Never `app/`, `chart/`, or `.github/`. A code change is
invisible to ArgoCD until the pipeline deploys it.

**2. Image tags are ArgoCD Helm parameters, not committed chart values.** The pipeline sets
the tag by patching the live `Application` object's `spec.source.helm.parameters`. A deploy
changes *cluster state, not Git*.

**3. The pipeline builds only from `app/` and `chart/`.** The `detect-changes` stage
classifies the diff; a change confined to `gitops/` is applied by ArgoCD, not by CI.

**4. Therefore `selfHeal: true` is safe.** The only thing ArgoCD manages is `gitops/`, and the
pipeline never writes there. There is nothing to fight.

Break rule 2 and you get the loop the other three exist to prevent. The `security` stage fails
the build if a committed `image.tag` ever appears in `gitops/apps/`, and the `verify` stage
re-checks `Synced` a minute after the patch to prove selfHeal didn't revert it.

## What rollback actually is

Not a git revert. Nothing was committed, so there is nothing to revert.

The `configure` stage captures the currently-deployed tag *before* patching. The `rollback`
stage — manual approval, never automatic — rewinds the Rollout to its previous revision.
One click, cluster state only, `gitops/` untouched.

## Release modes

One chart, three modes, mutually exclusive:

| Environment | Mode | Sync |
|---|---|---|
| `app-dev` | plain Deployment | manual |
| `app-bluegreen` | blue/green Rollout, manual promotion | automated |
| `app-canary` | canary Rollout: 20% → 50% → 80% with pauses | automated |

The plain `Deployment` template renders **only** when both mode switches are off — otherwise
two controllers would own the same pods. The `security` stage renders all three modes and
fails if a Deployment leaks into a Rollout mode, and fails again if both modes are set at once.

## Bootstrap

ArgoCD cannot install itself from a repo it has not read yet, so exactly one step is
imperative. Terraform does it, in the `provision` stage:

1. `helm_release` ArgoCD (+ `server.insecure`, since ingress terminates TLS)
2. `helm_release` Argo Rollouts
3. `helm_release` ingress-nginx (NLB)
4. apply `gitops/root-app.yaml` — the root app-of-apps

Everything after that is reconciled by ArgoCD from `gitops/apps/`.

`gitops/apps/` is a Helm chart rather than plain YAML because two values are unknowable until
the cluster exists: this repo's URL and the ECR URL (which embeds the account id). Terraform
passes them as parameters on the root Application. The alternative — having the pipeline patch
them in later — would write into `gitops/` and break rule 4.

The root Application is applied with the `kubectl` provider, not `kubernetes_manifest`: the
latter validates CRD schemas at *plan* time and so cannot create a resource whose CRD does not
exist yet, which is exactly the first-apply case.

## Operating it

```bash
aws eks update-kubeconfig --region us-east-1 --name <project>

kubectl get applications -n argocd            # what ArgoCD thinks
kubectl argo rollouts get rollout app -n app-canary --watch
kubectl argo rollouts promote app -n app-canary     # skip a canary pause
kubectl argo rollouts undo app -n app-canary        # what the rollback stage runs
```

Console at `/argocd`, dashboards at `/grafana` on the ingress hostname.

Initial passwords (never committed):
```bash
kubectl get secret argocd-initial-admin-secret -n argocd -o jsonpath='{.data.password}' | base64 -d
kubectl get secret observability-grafana -n monitoring -o jsonpath='{.data.admin-password}' | base64 -d
```

## Deliberate trade-offs

- **Single NAT gateway**, not one per AZ. Saves ~£30/mo; losing that AZ removes egress for
  private subnets in both.
- **No Alertmanager.** Nothing to route alerts to yet, so running it would only cost memory
  and imply coverage that doesn't exist.
- **No database.** None was required. Adding one is additive.
- **HTTP, not HTTPS.** No domain was supplied. cert-manager + a real hostname is the next step.
- **Flux is not installed.** See `flux/README.md`.

Running cost is roughly **£200–280/mo**, accruing from the moment `provision` goes green. The
EKS control plane alone is ~£58/mo whether or not anything runs on it.
