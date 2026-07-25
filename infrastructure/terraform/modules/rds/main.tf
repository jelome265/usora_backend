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
  description = "Private subnet IDs for DB placement"
}

variable "database_name" {
  type        = string
  description = "Database name"
  default     = "usora"
}

variable "instance_class" {
  type        = string
  description = "RDS instance class"
  default     = "db.r6g.xlarge"
}

variable "multi_az" {
  type        = bool
  description = "Enable Multi-AZ deployment"
  default     = false
}

variable "replica_count" {
  type        = number
  description = "Number of read replicas"
  default     = 0
}

variable "allocated_storage" {
  type        = number
  description = "Allocated storage size in GB"
  default     = 500
}

variable "iops" {
  type        = number
  description = "Provisioned IOPS"
  default     = 10000
}

variable "storage_type" {
  type        = string
  description = "Storage type"
  default     = "gp3"
}

variable "backup_retention_days" {
  type        = number
  description = "Backup retention period in days"
  default     = 35
}

variable "deletion_protection" {
  type        = bool
  description = "Enable deletion protection"
  default     = true
}

variable "enable_performance_insights" {
  type        = bool
  description = "Enable Performance Insights"
  default     = true
}

variable "enable_enhanced_monitoring" {
  type        = bool
  description = "Enable Enhanced Monitoring"
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

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "zone-type"
    values = ["availability-zone"]
  }
}

resource "aws_db_subnet_group" "main" {
  name        = "-db-subnet-group"
  description = "Database subnet group for "
  subnet_ids  = var.private_subnet_ids

  tags = local.common_tags
}

resource "aws_db_parameter_group" "main" {
  name        = "-postgres16-pg"
  family      = "postgres16"
  description = "Custom parameter group for PostgreSQL 16"

  parameter {
    name  = "shared_buffers"
    value = "1073741824"
  }

  parameter {
    name  = "effective_cache_size"
    value = "3221225472"
  }

  parameter {
    name  = "maintenance_work_mem"
    value = "209715200"
  }

  parameter {
    name  = "random_page_cost"
    value = "1.1"
  }

  parameter {
    name  = "work_mem"
    value = "8388608"
  }

  parameter {
    name  = "max_connections"
    value = "500"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  parameter {
    name  = "idle_in_transaction_session_timeout"
    value = "300000"
  }

  parameter {
    name  = "log_checkpoints"
    value = "on"
  }

  parameter {
    name  = "log_connections"
    value = "on"
  }

  parameter {
    name  = "log_disconnections"
    value = "on"
  }

  parameter {
    name  = "log_lock_waits"
    value = "on"
  }

  parameter {
    name  = "log_temp_files"
    value = "0"
  }

  parameter {
    name  = "wal_buffers"
    value = "16384"
  }

  parameter {
    name  = "wal_level"
    value = "logical"
  }

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  tags = local.common_tags
}

resource "aws_kms_key" "rds" {
  description             = "KMS key for RDS encryption"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = local.common_tags
}

resource "aws_db_instance" "primary" {
  identifier = "-db-primary"

  engine         = "postgres"
  engine_version = "16.3"
  instance_class = var.instance_class

  db_name  = var.database_name
  username = "usora_admin"

  manage_master_user_password = true

  allocated_storage     = var.allocated_storage
  storage_type          = var.storage_type
  iops                  = var.iops
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.rds.arn

  db_subnet_group_name   = aws_db_subnet_group.main.name
  parameter_group_name   = aws_db_parameter_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  multi_az               = var.multi_az
  publicly_accessible    = false

  backup_retention_period = var.backup_retention_days
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:05:00-sun:06:00"
  deletion_protection     = var.deletion_protection
  skip_final_snapshot     = false
  final_snapshot_identifier = "-db-final-"

  performance_insights_enabled          = var.enable_performance_insights
  performance_insights_retention_period = 7
  performance_insights_kms_key_id       = aws_kms_key.rds.arn

  monitoring_interval = var.enable_enhanced_monitoring ? 30 : 0
  monitoring_role_arn = var.enable_enhanced_monitoring ? aws_iam_role.enhanced_monitoring[0].arn : null

  enabled_cloudwatch_logs_exports = [
    "postgresql",
    "upgrade"
  ]

  auto_minor_version_upgrade = true

  tags = local.common_tags
}

resource "aws_iam_role" "enhanced_monitoring" {
  count = var.enable_enhanced_monitoring ? 1 : 0

  name = "-rds-monitoring-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "monitoring.rds.amazonaws.com"
        }
      }
    ]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "enhanced_monitoring" {
  count = var.enable_enhanced_monitoring ? 1 : 0

  policy_arn = "arn::iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  role       = aws_iam_role.enhanced_monitoring[0].name
}

data "aws_partition" "current" {}

resource "aws_security_group" "rds" {
  name        = "-rds-sg"
  description = "Security group for RDS PostgreSQL"
  vpc_id      = var.vpc_id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    cidr_blocks     = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "-rds-sg"
  })
}

variable "vpc_cidr" {
  type        = string
  description = "VPC CIDR block"
  default     = "10.0.0.0/16"
}

# Read Replicas
resource "aws_db_instance" "replica" {
  count = var.replica_count

  identifier = "-db-replica-"

  engine         = "postgres"
  engine_version = "16.3"
  instance_class = var.instance_class

  replicate_source_db = aws_db_instance.primary.identifier

  allocated_storage = var.allocated_storage
  storage_type      = var.storage_type
  iops              = var.iops
  storage_encrypted = true
  kms_key_id        = aws_kms_key.rds.arn

  db_subnet_group_name   = aws_db_subnet_group.main.name
  parameter_group_name   = aws_db_parameter_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  publicly_accessible   = false
  deletion_protection   = var.deletion_protection
  skip_final_snapshot   = true

  performance_insights_enabled          = var.enable_performance_insights
  performance_insights_retention_period = 7
  performance_insights_kms_key_id       = aws_kms_key.rds.arn

  monitoring_interval = var.enable_enhanced_monitoring ? 30 : 0
  monitoring_role_arn = var.enable_enhanced_monitoring ? aws_iam_role.enhanced_monitoring[0].arn : null

  enabled_cloudwatch_logs_exports = [
    "postgresql",
    "upgrade"
  ]

  auto_minor_version_upgrade = true

  tags = local.common_tags
}

output "endpoint" {
  value = aws_db_instance.primary.endpoint
}

output "reader_endpoint" {
  value = var.replica_count > 0 ? aws_db_instance.replica[0].endpoint : aws_db_instance.primary.endpoint
}

output "port" {
  value = aws_db_instance.primary.port
}

output "db_name" {
  value = aws_db_instance.primary.db_name
}

output "db_identifier" {
  value = aws_db_instance.primary.identifier
}

output "replica_endpoints" {
  value = var.replica_count > 0 ? aws_db_instance.replica[*].endpoint : []
}

output "db_subnet_group_name" {
  value = aws_db_subnet_group.main.name
}

output "security_group_id" {
  value = aws_security_group.rds.id
}

output "kms_key_arn" {
  value = aws_kms_key.rds.arn
}