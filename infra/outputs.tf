# The configure / verify / rollback stages each re-init the backend and read
# these themselves (the self-sufficient job rule) rather than receiving them
# through `needs` outputs — values derived from PROJECT_NAME are secret-masked
# and GitHub silently drops job outputs containing secret substrings.

output "cluster_name" {
  description = "EKS cluster name; used by `aws eks update-kubeconfig`."
  value       = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.main.endpoint
}

output "ecr_repository_url" {
  description = "Image repository the chart pulls from."
  value       = aws_ecr_repository.app.repository_url
}

output "region" {
  value = var.region
}

output "vpc_id" {
  value = aws_vpc.main.id
}
