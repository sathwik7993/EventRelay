output "alb_dns_name" {
  description = "Public DNS name of the API load balancer."
  value       = aws_lb.api.dns_name
}

output "queue_url" {
  description = "Delivery queue URL."
  value       = aws_sqs_queue.deliveries.url
}

output "queue_dlq_url" {
  description = "Poison-message DLQ URL (application-level DLQ lives in PostgreSQL)."
  value       = aws_sqs_queue.deliveries_dlq.url
}

output "cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}
