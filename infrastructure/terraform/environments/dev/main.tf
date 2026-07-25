terraform {
  backend "s3" {
    bucket         = "usora-terraform-state-dev"
    key            = "dev/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "usora-terraform-locks-dev"
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
  environment = "dev"
  azs         = slice(data.aws_availability_zones.available.names, 0, 2)

  tags = {
    Environment = "dev"
    Project     = "usora"
    ManagedBy   = "terraform"
  }
}

module "vpc" {
  source = "../../modules/vpc"

  environment         = local.environment
  vpc_cidr            = "10.0.0.0/16"
  availability_zones  = local.azs
  single_nat_gateway  = true
  enable_nat_gateway  = true
  enable_flow_logs    = false
  enable_vpc_endpoints = false
  tags                = local.tags
}

module "eks" {
  source = "../../modules/eks"

  environment        = local.environment
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  tags               = local.tags

  node_groups = {
    gateway = {
      instance_types = ["t3.medium"]
      min_size       = 1
      max_size       = 3
      desired_size   = 1
      capacity_type  = "ON_DEMAND"
      k8s_labels     = { "usora.io/node-type" = "gateway" }
    }
  }
}

module "rds" {
  source = "../../modules/rds"

  environment              = local.environment
  vpc_id                   = module.vpc.vpc_id
  vpc_cidr                 = module.vpc.vpc_cidr
  private_subnet_ids       = module.vpc.private_subnet_ids
  instance_class           = "db.t3.medium"
  multi_az                 = false
  replica_count            = 0
  allocated_storage        = 100
  iops                     = 3000
  storage_type             = "gp3"
  backup_retention_days    = 7
  deletion_protection      = false
  enable_performance_insights = false
  enable_enhanced_monitoring  = false
  tags                     = local.tags
}
