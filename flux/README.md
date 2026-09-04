# flux/ — reserved, not installed

Flux is deliberately **not** deployed. ArgoCD and Flux are competing reconcilers; pointed at
the same paths they fight in a sync loop. In a monorepo the difference is sharper:

- **ArgoCD** keeps deploy state on an in-cluster `Application` object and the pipeline
  *patches* it — deploys never touch Git, so there is no loop to guard against.
- **Flux** keeps deploy state in Git and the pipeline *commits* it — in the same repo it is
  triggered from. That commit will retrigger CI unless it is guarded.

Running only one engine, ArgoCD's model is simpler to keep loop-free. That is the choice here.

## Adding Flux later is additive, not a rewrite

The two guards Flux needs are already in place, so nothing has to be redesigned:

1. `detect-changes` already excludes `flux/**` from what triggers a build.
2. The write-back commit must carry `[skip ci]`.

**Both**, not either — the trigger exclusion protects against a run being started, `[skip ci]`
protects against the commit being picked up by any other trigger path.

To enable:

1. `helm_release "flux2"` (or `flux install`) in `infra/bootstrap.tf`.
2. A Git-auth secret for the Flux `GitRepository`.
3. In this folder: a `GitRepository` pointing at this repo, a `HelmRelease` with
   `chart: ./chart/app`, and `valuesFrom` a ConfigMap holding the image tag.
4. `flux/image-values.yaml` — the ConfigMap. **This is the one file a deploy writes to Git.**
5. A parallel deploy job that writes the tag into that file and pushes with `[skip ci]`.

Keep the paths non-overlapping: Flux reconciles `flux/`, ArgoCD reconciles `gitops/`. If both
engines ever point at the same path, they will revert each other indefinitely.
