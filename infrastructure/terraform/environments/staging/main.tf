terraform {
  backend "s3" {
    bucket         = "usora-terraform-state-staging"
    key            = "staging/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "usora-terraform-locks-staging"
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "us-east-1"
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  environment = "staging"
  azs         = slice(data.aws_availability_zones.available.names, 0, 3)

  tags = {
    Environment = "staging"
    Project     = "usora"
    ManagedBy   = "terraform"
  }
}

module "vpc" {
  source = "../../modules/vpc"

  environment         = local.environment
  vpc_cidr            = "10.1.0.0/16"
  availability_zones  = local.azs
  single_nat_gateway  = false
  enable_nat_gateway  = true
  enable_flow_logs    = true
  enable_vpc_endpoints = true
  tags                = local.tags
}

module "eks" {
  source = "../../modules/eks"

  environment        = local.environment
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  tags               = local.tags

  node_groups = {
    gateway-spot = {
      instance_types = ["t3.large"]
      min_size       = 2
      max_size       = 5
      desired_size   = 2
      capacity_type  = "SPOT"
      k8s_labels     = { "usora.io/node-type" = "gateway" }
    }
    gateway-ondemand = {
      instance_types = ["t3.large"]
      min_size       = 1
      max_size       = 3
      desired_size   = 1
      capacity_type  = "ON_DEMAND"
      k8s_labels     = { "usora.io/node-type" = "gateway" }
    }
    compute-spot = {
      instance_types = ["c6i.2xlarge"]
      min_size       = 2
      max_size       = 10
      desired_size   = 2
      capacity_type  = "SPOT"
      k8s_labels     = { "usora.io/node-type" = "compute" }
    }
    compute-ondemand = {
      instance_types = ["c6i.2xlarge"]
      min_size       = 1
      max_size       = 5
      desired_size   = 1
      capacity_type  = "ON_DEMAND"
      k8s_labels     = { "usora.io/node-type" = "compute" }
    }
    orchestration = {
      instance_types = ["m6i.large"]
      min_size       = 1
      max_size       = 5
      desired_size   = 1
      capacity_type  = "ON_DEMAND"
      k8s_labels     = { "usora.io/node-type" = "orchestration" }
    }
    data = {
      instance_types = ["r6i.large"]
      min_size       = 1
      max_size       = 5
      desired_size   = 1
      capacity_type  = "ON_DEMAND"
      k8s_labels     = { "usora.io/node-type" = "data" }
    }
  }
}

module "rds" {
  source = "../../modules/rds"

  environment              = local.environment
  vpc_id                   = module.vpc.vpc_id
  vpc_cidr                 = module.vpc.vpc_cidr
  private_subnet_ids       = module.vpc.private_subnet_ids
  instance_class           = "db.r6g.large"
  multi_az                 = true
  replica_count            = 0
  allocated_storage        = 200
  iops                     = 3000
  storage_type             = "gp3"
  backup_retention_days    = 14
  deletion_protection      = false
  enable_performance_insights = true
  enable_enhanced_monitoring  = true
  tags                     = local.tags
}

module "msk" {
  source = "../../modules/msk"

  environment        = local.environment
  vpc_id             = module.vpc.vpc_id
  vpc_cidr           = module.vpc.vpc_cidr
  private_subnet_ids = module.vpc.private_subnet_ids
  broker_count       = 2
  broker_instance_type = "kafka.t3.small"
  tags               = local.tags
}

module "redis" {
  source = "../../modules/elasticache"

  environment        = local.environment
  vpc_id             = module.vpc.vpc_id
  vpc_cidr           = module.vpc.vpc_cidr
  private_subnet_ids = module.vpc.private_subnet_ids
  node_type          = "cache.r6g.large"
  num_shards         = 2
  replicas_per_shard = 1
  automatic_failover = true
  tags               = local.tags
}
