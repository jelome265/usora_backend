variable "environment" {
  type        = string
  description = "Environment name (dev, staging, prod)"
}

variable "vpc_id" {
  type        = string
  description = "VPC ID"
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Private subnet IDs for Redis placement"
}

variable "node_type" {
  type        = string
  description = "ElastiCache node type"
  default     = "cache.r6g.large"
}

variable "num_shards" {
  type        = number
  description = "Number of Redis shards (cluster mode)"
  default     = 3
}

variable "replicas_per_shard" {
  type        = number
  description = "Number of read replicas per shard"
  default     = 1
}

variable "automatic_failover" {
  type        = bool
  description = "Enable automatic failover"
  default     = true
}

variable "backup_retention_days" {
  type        = number
  description = "Backup retention period in days"
  default     = 7
}

variable "enable_encryption_at_rest" {
  type        = bool
  description = "Enable encryption at rest"
  default     = true
}

variable "enable_encryption_in_transit" {
  type        = bool
  description = "Enable encryption in transit"
  default     = true
}

variable "enable_auth_token" {
  type        = bool
  description = "Enable Redis AUTH token"
  default     = true
}

variable "tags" {
  type        = map(string)
  description = "Tags to apply to resources"
  default     = {}
}

locals {
  name_prefix = "usora-"

  common_tags = merge(var.tags, {
    Environment = var.environment
    ManagedBy   = "Terraform"
    Service     = "usora"
  })
}

data "aws_region" "current" {}
data "aws_partition" "current" {}

resource "aws_kms_key" "redis" {
  description             = "KMS key for ElastiCache Redis encryption"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = local.common_tags
}

resource "aws_security_group" "redis" {
  name        = "-redis-sg"
  description = "Security group for ElastiCache Redis"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "-redis-sg"
  })
}

variable "vpc_cidr" {
  type        = string
  description = "VPC CIDR block"
  default     = "10.0.0.0/16"
}

resource "aws_elasticache_subnet_group" "main" {
  name        = "-redis-subnet-group"
  description = "ElastiCache subnet group for "
  subnet_ids  = var.private_subnet_ids

  tags = local.common_tags
}

resource "aws_elasticache_parameter_group" "redis" {
  name        = "-redis7-pg"
  family      = "redis7"
  description = "Custom parameter group for Redis 7"

  parameter {
    name  = "reserved-memory-percent"
    value = "25"
  }

  parameter {
    name  = "timeout"
    value = "300"
  }

  parameter {
    name  = "maxmemory-policy"
    value = "allkeys-lru"
  }

  parameter {
    name  = "notify-keyspace-events"
    value = "Ex"
  }

  parameter {
    name  = "activedefrag"
    value = "yes"
  }

  parameter {
    name  = "lfu-log-factor"
    value = "10"
  }

  parameter {
    name  = "lfu-decay-time"
    value = "1"
  }

  tags = local.common_tags
}

resource "random_password" "auth_token" {
  count = var.enable_auth_token ? 1 : 0

  length  = 32
  special = false
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id          = "-redis"
  description                   = "ElastiCache Redis cluster for "
  node_type                     = var.node_type
  num_node_groups               = var.num_shards
  replicas_per_node_group       = var.replicas_per_shard
  port                          = 6379

  automatic_failover_enabled    = var.automatic_failover
  multi_az_enabled              = var.automatic_failover && var.replicas_per_shard > 0 ? true : false

  parameter_group_name          = aws_elasticache_parameter_group.redis.name
  subnet_group_name             = aws_elasticache_subnet_group.main.name
  security_group_ids            = [aws_security_group.redis.id]

  engine                        = "redis"
  engine_version                = "7.1"

  at_rest_encryption_enabled    = var.enable_encryption_at_rest
  kms_key_id                    = var.enable_encryption_at_rest ? aws_kms_key.redis.arn : null

  transit_encryption_enabled    = var.enable_encryption_in_transit
  auth_token                    = var.enable_auth_token ? random_password.auth_token[0].result : null

  snapshot_retention_limit      = var.backup_retention_days
  snapshot_window               = "04:00-05:00"
  maintenance_window            = "sun:06:00-sun:07:00"

  auto_minor_version_upgrade    = true

  data_tiering_enabled          = false

  tags = local.common_tags
}

output "primary_endpoint" {
  value = aws_elasticache_replication_group.main.primary_endpoint_address
}

output "reader_endpoint" {
  value = aws_elasticache_replication_group.main.reader_endpoint_address
}

output "port" {
  value = aws_elasticache_replication_group.main.port
}

output "auth_token" {
  value     = var.enable_auth_token ? random_password.auth_token[0].result : null
  sensitive = true
}

output "replication_group_id" {
  value = aws_elasticache_replication_group.main.replication_group_id
}

output "security_group_id" {
  value = aws_security_group.redis.id
}

output "parameter_group_name" {
  value = aws_elasticache_parameter_group.redis.name
}

output "subnet_group_name" {
  value = aws_elasticache_subnet_group.main.name
}

output "num_shards" {
  value = var.num_shards
}

output "num_replicas" {
  value = var.replicas_per_shard
}