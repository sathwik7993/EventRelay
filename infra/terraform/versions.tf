terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Remote state (uncomment and create the bucket/table before use):
  # backend "s3" {
  #   bucket         = "eventrelay-tfstate"
  #   key            = "prod/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "eventrelay-tflock"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "EventRelay"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
