terraform {
  required_version = ">= 1.9.0"

  # Empty by design — bucket/key/region arrive via -backend-config at init.
  backend "s3" {}

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.15"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.33"
    }
    # Used ONLY for the root app-of-apps Application.
    # hashicorp/kubernetes' kubernetes_manifest validates CRD schemas during
    # PLAN, so it cannot create a custom resource whose CRD does not exist yet
    # — exactly the first-apply case here. This provider applies raw YAML the
    # way `kubectl apply` does, with no plan-time schema lookup.
    kubectl = {
      source  = "gavinbunney/kubectl"
      version = "~> 1.14"
    }
    # Reads the OIDC issuer's certificate thumbprint for the IRSA provider.
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}
