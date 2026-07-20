# EventRelay on ECS Fargate: ALB -> API tasks, SQS -> dispatcher tasks that
# autoscale on queue depth.

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

locals {
  name = "eventrelay-${var.environment}"
}

# ---------------------------------------------------------------------------
# Queue
# ---------------------------------------------------------------------------
resource "aws_sqs_queue" "deliveries_dlq" {
  name                      = "${local.name}-deliveries-dlq"
  message_retention_seconds = 1209600 # 14 days
}

resource "aws_sqs_queue" "deliveries" {
  name                       = "${local.name}-deliveries"
  visibility_timeout_seconds = var.queue_visibility_timeout

  # Application-level retries/DLQ are authoritative; this redrive policy only
  # catches messages that repeatedly fail to be processed at all (poison pills).
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.deliveries_dlq.arn
    maxReceiveCount     = 10
  })
}

# ---------------------------------------------------------------------------
# Security groups
# ---------------------------------------------------------------------------
resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Public ingress to the ALB"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "tasks" {
  name        = "${local.name}-tasks"
  description = "ECS tasks"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "ALB to API"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ---------------------------------------------------------------------------
# IAM
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "assume_ecs_tasks" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.assume_ecs_tasks.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "${local.name}-read-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [var.db_password_secret_arn]
    }]
  })
}

resource "aws_iam_role" "task" {
  name               = "${local.name}-task"
  assume_role_policy = data.aws_iam_policy_document.assume_ecs_tasks.json
}

# Least privilege: only the operations the dispatcher actually performs.
resource "aws_iam_role_policy" "task_sqs" {
  name = "${local.name}-sqs"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "sqs:SendMessage",
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueUrl",
        "sqs:GetQueueAttributes",
        "sqs:CreateQueue"
      ]
      Resource = [aws_sqs_queue.deliveries.arn, aws_sqs_queue.deliveries_dlq.arn]
    }]
  })
}

# ---------------------------------------------------------------------------
# Logging & cluster
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${local.name}/api"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "dispatcher" {
  name              = "/ecs/${local.name}/dispatcher"
  retention_in_days = 30
}

resource "aws_ecs_cluster" "this" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# ---------------------------------------------------------------------------
# Load balancer (API only; the dispatcher takes no inbound traffic)
# ---------------------------------------------------------------------------
resource "aws_lb" "api" {
  name               = "${local.name}-alb"
  load_balancer_type = "application"
  subnets            = data.aws_subnets.default.ids
  security_groups    = [aws_security_group.alb.id]
}

resource "aws_lb_target_group" "api" {
  name        = "${local.name}-api"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = data.aws_vpc.default.id

  health_check {
    path                = "/actuator/health/readiness"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
  }

  deregistration_delay = 30
}

# ---------------------------------------------------------------------------
# Task definitions
# ---------------------------------------------------------------------------
locals {
  common_env = [
    { name = "DB_URL", value = var.db_url },
    { name = "DB_USERNAME", value = var.db_username },
    { name = "REDIS_HOST", value = var.redis_host },
    { name = "AWS_REGION", value = var.aws_region },
    { name = "SQS_QUEUE_NAME", value = aws_sqs_queue.deliveries.name },
    { name = "SQS_ENDPOINT", value = "" },
  ]

  db_secret = [
    { name = "DB_PASSWORD", valueFrom = var.db_password_secret_arn }
  ]
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name        = "api"
    image       = var.api_image
    essential   = true
    environment = concat(local.common_env, [{ name = "SERVER_PORT", value = "8080" }])
    secrets     = local.db_secret
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    healthCheck = {
      command     = ["CMD-SHELL", "curl -fsS http://localhost:8080/actuator/health/readiness || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.api.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "api"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "dispatcher" {
  family                   = "${local.name}-dispatcher"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name        = "dispatcher"
    image       = var.dispatcher_image
    essential   = true
    environment = concat(local.common_env, [{ name = "SERVER_PORT", value = "8081" }])
    secrets     = local.db_secret
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.dispatcher.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "dispatcher"
      }
    }
  }])
}

# ---------------------------------------------------------------------------
# Services
# ---------------------------------------------------------------------------
resource "aws_ecs_service" "api" {
  name            = "${local.name}-api"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  # Rolling, zero-downtime deploys.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  depends_on = [aws_lb_target_group.api]
}

resource "aws_ecs_service" "dispatcher" {
  name            = "${local.name}-dispatcher"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.dispatcher.arn
  desired_count   = var.dispatcher_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }
}

# ---------------------------------------------------------------------------
# Autoscale dispatchers on queue backlog
# ---------------------------------------------------------------------------
resource "aws_appautoscaling_target" "dispatcher" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.dispatcher.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.dispatcher_desired_count
  max_capacity       = var.dispatcher_max_count
}

resource "aws_appautoscaling_policy" "dispatcher_queue_depth" {
  name               = "${local.name}-dispatcher-queue-depth"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.dispatcher.service_namespace
  resource_id        = aws_appautoscaling_target.dispatcher.resource_id
  scalable_dimension = aws_appautoscaling_target.dispatcher.scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = 1000 # visible messages per task
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    customized_metric_specification {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      statistic   = "Average"

      dimensions {
        name  = "QueueName"
        value = aws_sqs_queue.deliveries.name
      }
    }
  }
}
