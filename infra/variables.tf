variable "project_name" {
  description = "Branch-scoped project name; prefixes every resource."
  type        = string
}

variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "repo_url" {
  description = "HTTPS URL of this monorepo. Every ArgoCD Application's repoURL."
  type        = string
}

variable "branch" {
  description = "Branch ArgoCD tracks (targetRevision)."
  type        = string
  default     = "main"
}

variable "cluster_version" {
  description = "EKS control plane version. Keep inside STANDARD support (1.33-1.36 as of 2026-07); 1.30-1.32 are extended support and cost extra."
  type        = string
  default     = "1.33"
}

variable "node_instance_type" {
  description = "Managed node group instance type. The full platform (ArgoCD + Rollouts + kube-prometheus-stack + ingress-nginx + 3 app namespaces) does not fit on t3.medium."
  type        = string
  default     = "t3.large"
}

variable "node_desired_size" {
  type    = number
  default = 3
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  type    = number
  default = 5
}

variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
}

variable "argocd_chart_version" {
  description = "Pinned argo-cd chart version. Unpinned charts break green pipelines on upstream release."
  type        = string
  default     = "7.7.11"
}

variable "argo_rollouts_chart_version" {
  type    = string
  default = "2.38.0"
}

variable "ingress_nginx_chart_version" {
  type    = string
  default = "4.11.3"
}
