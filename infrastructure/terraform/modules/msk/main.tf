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
  description = "Private subnet IDs for broker placement"
}

variable "broker_count" {
  type        = number
  description = "Number of broker nodes"
  default     = 3
}

variable "broker_instance_type" {
  type        = string
  description = "MSK broker instance type"
  default     = "kafka.t3.small"
}

variable "kafka_version" {
  type        = string
  description = "Apache Kafka version"
  default     = "3.6.0"
}

variable "enhanced_monitoring" {
  type        = string
  description = "Enhanced monitoring level"
  default     = "PER_TOPIC_PER_PARTITION"
}

variable "enable_sasl_scram" {
  type        = bool
  description = "Enable SASL/SCRAM authentication"
  default     = true
}

variable "enable_tls_auth" {
  type        = bool
  description = "Enable TLS certificate authentication"
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
data "aws_caller_identity" "current" {}

resource "aws_kms_key" "msk" {
  description             = "KMS key for MSK encryption"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = local.common_tags
}

resource "aws_security_group" "msk" {
  name        = "-msk-sg"
  description = "Security group for MSK Kafka"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 9092
    to_port     = 9098
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  ingress {
    from_port   = 2181
    to_port     = 2181
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
    Name = "-msk-sg"
  })
}

variable "vpc_cidr" {
  type        = string
  description = "VPC CIDR block"
  default     = "10.0.0.0/16"
}

resource "aws_msk_configuration" "main" {
  name           = "-msk-config"
  kafka_versions = [var.kafka_version]

  server_properties = <<-PROPERTIES
    auto.create.topics.enable = false
    default.replication.factor = 3
    min.insync.replicas = 2
    num.io.threads = 8
    num.network.threads = 8
    num.partitions = 3
    log.retention.hours = 168
    log.retention.bytes = -1
    log.segment.bytes = 1073741824
    log.retention.check.interval.ms = 300000
    group.initial.rebalance.delay.ms = 3000
    offsets.topic.replication.factor = 3
    transaction.state.log.replication.factor = 3
    transaction.state.log.min.isr = 2
    compression.type = snappy
    message.max.bytes = 10485760
    replica.fetch.max.bytes = 10485760
    max.request.size = 10485760
    unclean.leader.election.enable = false
  PROPERTIES

  tags = local.common_tags
}

resource "aws_msk_cluster" "main" {
  cluster_name           = "-msk"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = 500
        provisioned_throughput {
          enabled   = true
          throughput = 250
        }
      }
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.msk.arn

    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  client_authentication {
    dynamic "sasl" {
      for_each = var.enable_sasl_scram ? [1] : []
      content {
        scram = true
      }
    }

    dynamic "tls" {
      for_each = var.enable_tls_auth ? [1] : []
      content {
        certificate_authority_arns = var.enable_tls_auth ? [aws_acm_certificate.msk[0].arn] : []
      }
    }

    unauthenticated_access = false
  }

  configuration_info {
    arn      = aws_msk_configuration.main.arn
    revision = aws_msk_configuration.main.latest_revision
  }

  enhanced_monitoring = var.enhanced_monitoring

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
      firehose {
        enabled = false
      }
      s3 {
        enabled = false
      }
    }
  }

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  tags = local.common_tags
}

resource "aws_acm_certificate" "msk" {
  count = var.enable_tls_auth ? 1 : 0

  domain_name       = "msk..usora.internal"
  validation_method = "DNS"

  tags = local.common_tags
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/aws/msk//broker-logs"
  retention_in_days = 90

  tags = local.common_tags
}

# SASL/SCRAM secret
resource "aws_secretsmanager_secret" "msk_scram" {
  count = var.enable_sasl_scram ? 1 : 0

  name = "-msk-scram"

  kms_key_id = aws_kms_key.msk.arn

  tags = local.common_tags
}

resource "aws_secretsmanager_secret_version" "msk_scram" {
  count = var.enable_sasl_scram ? 1 : 0

  secret_id = aws_secretsmanager_secret.msk_scram[0].id
  secret_string = jsonencode({
    username = "usora_kafka_user"
    password = random_password.msk_scram[0].result
  })
}

resource "random_password" "msk_scram" {
  count = var.enable_sasl_scram ? 1 : 0

  length  = 32
  special = false
}

resource "aws_msk_scram_secret_association" "main" {
  count = var.enable_sasl_scram ? 1 : 0

  cluster_arn     = aws_msk_cluster.main.arn
  secret_arn_list = [aws_secretsmanager_secret.msk_scram[0].arn]
}

output "cluster_arn" {
  value = aws_msk_cluster.main.arn
}

output "cluster_name" {
  value = aws_msk_cluster.main.cluster_name
}

output "bootstrap_brokers" {
  value = aws_msk_cluster.main.bootstrap_brokers
}

output "bootstrap_brokers_tls" {
  value = aws_msk_cluster.main.bootstrap_brokers_tls
}

output "bootstrap_brokers_sasl_scram" {
  value = aws_msk_cluster.main.bootstrap_brokers_sasl_scram
}

output "zookeeper_connect" {
  value = aws_msk_cluster.main.zookeeper_connect_string
}

output "security_group_id" {
  value = aws_security_group.msk.id
}

output "configuration_arn" {
  value = aws_msk_configuration.main.arn
}

output "cloudwatch_log_group" {
  value = aws_cloudwatch_log_group.msk.name
}