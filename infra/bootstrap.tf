# =============================================================================
# THE ONE BOOTSTRAP STEP (user spec §2.1-2.3)
#
# "Install ArgoCD by hand once, then let ArgoCD install everything else from
#  <REPO>/gitops/."
#
# The spec's three manual commands become three Terraform resources. Same
# charts, same versions, same end state — but reproducible and re-runnable.
# Everything BELOW the root Application is reconciled by ArgoCD from gitops/,
# exactly as specified. Terraform never touches gitops/ CONTENT — it only
# applies the root object once and hands it the values that cannot be known
# until the cluster exists.
# =============================================================================

# ---------- §2.1 ArgoCD ----------

resource "helm_release" "argocd" {
  name             = "argocd"
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-cd"
  version          = var.argocd_chart_version
  namespace        = "argocd"
  create_namespace = true

  # The root Application below needs the Application CRD registered first.
  wait    = true
  timeout = 900

  values = [yamlencode({
    # Spec §2.1: "If behind a TLS-terminating gateway, enable insecure mode via
    # the argocd-cmd-params-cm param". ingress-nginx terminates TLS in front of
    # the server, so the server must not also try to.
    configs = {
      params = {
        "server.insecure" = true
      }
    }

    server = {
      replicas = 1
      ingress = {
        enabled          = true
        ingressClassName = "nginx"
        path             = "/argocd"
        pathType         = "Prefix"
      }
      # Served under a sub-path on the shared ingress, so the server has to
      # know its own base href or the console loads a blank page.
      extraArgs = ["--basehref", "/argocd", "--rootpath", "/argocd"]
    }

    # Trap 2 (part 1): enabling controller metrics exposes port 8082, but the
    # chart creates NO Service in front of it. The Service + ServiceMonitor
    # that make it scrapeable live in gitops/scrapes/, applied by ArgoCD.
    controller = {
      metrics = {
        enabled        = true
        serviceMonitor = { enabled = false }
      }
    }

    repoServer = { metrics = { enabled = true } }
  })]

  depends_on = [
    aws_eks_node_group.main,
    aws_eks_addon.coredns,
  ]
}

# ---------- §2.2 Argo Rollouts ----------

resource "helm_release" "argo_rollouts" {
  name             = "argo-rollouts"
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-rollouts"
  version          = var.argo_rollouts_chart_version
  namespace        = "argo-rollouts"
  create_namespace = true

  wait    = true
  timeout = 600

  values = [yamlencode({
    controller = {
      metrics = { enabled = true }
    }
    dashboard = { enabled = false }
  })]

  depends_on = [aws_eks_node_group.main]
}

# ---------- Ingress ----------
# Spec §2.0 lists the gateway as pre-existing and do-not-modify. This cluster
# is greenfield, so there is nothing to inherit — we create it.

resource "helm_release" "ingress_nginx" {
  name             = "ingress-nginx"
  repository       = "https://kubernetes.github.io/ingress-nginx"
  chart            = "ingress-nginx"
  version          = var.ingress_nginx_chart_version
  namespace        = "ingress-nginx"
  create_namespace = true

  wait    = true
  timeout = 900

  values = [yamlencode({
    controller = {
      replicaCount = 2
      service = {
        type = "LoadBalancer"
        annotations = {
          "service.beta.kubernetes.io/aws-load-balancer-type"            = "nlb"
          "service.beta.kubernetes.io/aws-load-balancer-scheme"          = "internet-facing"
          "service.beta.kubernetes.io/aws-load-balancer-nlb-target-type" = "ip"
        }
      }
      metrics = { enabled = true }
    }
  })]

  depends_on = [aws_eks_node_group.main]
}

# ---------- §2.3 The root app-of-apps ----------
#
# The ONE object that cannot come from Git, because it is what tells ArgoCD
# where Git is. Everything below it is reconciled from gitops/apps/.
#
# Built inline with yamlencode rather than templatefile(): terraform's
# templatefile only reads paths inside the configuration directory, and this
# object needs values (the ECR URL) that do not exist until apply time.
# Inline means there is exactly ONE definition, so it cannot drift from a copy.
# gitops/root-app.yaml documents the same object for review.
#
# WHY gitops/apps/ IS A HELM CHART, NOT PLAIN YAML
# ------------------------------------------------
# Two facts are unknowable until the cluster exists: this repo's URL and the
# ECR URL (which embeds the AWS account id). Every child Application needs
# both. Having the PIPELINE patch them in later would mean writing into
# gitops/ — the one tree selfHeal owns — and would break spec §4 rule 4.
# Passing them as Helm parameters on the root Application keeps the pipeline
# out of gitops/ entirely. Terraform sets them once; they never change again.
#
# NOT kubernetes_manifest: that resource fetches the CRD's OpenAPI schema at
# PLAN time, so it cannot create a custom resource whose CRD does not exist
# yet — exactly the first-apply case here. kubectl_manifest applies raw YAML
# the way `kubectl apply` does, with no plan-time schema lookup.

resource "kubectl_manifest" "root_app" {
  yaml_body = yamlencode({
    apiVersion = "argoproj.io/v1alpha1"
    kind       = "Application"
    metadata = {
      name       = "root"
      namespace  = "argocd"
      finalizers = ["resources-finalizer.argocd.argoproj.io"]
    }
    spec = {
      project = "default"
      source = {
        repoURL        = var.repo_url
        targetRevision = var.branch
        path           = "gitops/apps" # watches ONLY this folder
        helm = {
          parameters = [
            { name = "repoURL", value = var.repo_url },
            { name = "imageRepository", value = aws_ecr_repository.app.repository_url },
            { name = "targetRevision", value = var.branch },
          ]
        }
      }
      destination = {
        server    = "https://kubernetes.default.svc"
        namespace = "argocd"
      }
      syncPolicy = {
        # Safe because the pipeline never writes to gitops/ (spec §4 rule 4).
        automated   = { prune = true, selfHeal = true }
        syncOptions = ["CreateNamespace=true"]
      }
    }
  })

  server_side_apply = true
  wait              = true

  depends_on = [
    helm_release.argocd,
    helm_release.argo_rollouts,
    helm_release.ingress_nginx,
    kubernetes_storage_class.gp3,
  ]
}

# Namespaces for the three app environments. ArgoCD's CreateNamespace=true
# would also make them; creating them here keeps the Applications strictly
# about workloads and lets the labels be set consistently.
resource "kubernetes_namespace" "app_envs" {
  for_each = toset(["app-dev", "app-bluegreen", "app-canary"])

  metadata {
    name = each.value
    labels = {
      "app.kubernetes.io/part-of" = var.project_name
    }
  }

  depends_on = [aws_eks_node_group.main]
}
