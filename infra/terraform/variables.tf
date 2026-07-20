variable "aws_region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Environment name (used in resource names and tags)."
  type        = string
  default     = "prod"
}

variable "api_image" {
  description = "Container image for the ingest API (e.g. <acct>.dkr.ecr.<region>.amazonaws.com/eventrelay-api:tag)."
  type        = string
}

variable "dispatcher_image" {
  description = "Container image for the dispatcher worker."
  type        = string
}

variable "api_desired_count" {
  description = "Number of API tasks."
  type        = number
  default     = 2
}

variable "dispatcher_desired_count" {
  description = "Baseline number of dispatcher tasks."
  type        = number
  default     = 2
}

variable "dispatcher_max_count" {
  description = "Maximum dispatcher tasks when the queue backs up."
  type        = number
  default     = 8
}

variable "task_cpu" {
  description = "Fargate task CPU units."
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Fargate task memory (MiB)."
  type        = number
  default     = 1024
}

variable "db_url" {
  description = "JDBC URL for PostgreSQL (RDS endpoint)."
  type        = string
}

variable "db_username" {
  description = "Database username."
  type        = string
  sensitive   = true
}

variable "db_password_secret_arn" {
  description = "Secrets Manager ARN holding the database password."
  type        = string
}

variable "redis_host" {
  description = "ElastiCache Redis primary endpoint."
  type        = string
}

variable "queue_visibility_timeout" {
  description = "SQS visibility timeout (seconds). Must exceed the delivery HTTP timeout."
  type        = number
  default     = 60
}
