terraform {
  backend "s3" {
    bucket         = "usora-terraform-state-prod"
    key            = "prod/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "usora-terraform-locks-prod"
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

provider "aws" {
  region = "eu-west-1"
  alias  = "dr"
}

provider "aws" {
  region = "ap-south-1"
  alias  = "apac"
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_route53_zone" "main" {
  name         = "usora.com"
  private_zone = false
}

data "aws_lb" "api" {
  tags = {
    "usora.io/environment" = "prod"
    "usora.io/component"   = "api"
  }
}

locals {
  environment = "prod"
  azs         = slice(data.aws_availability_zones.available.names, 0, 3)
  domain_name = "api.usora.com"

  tags = {
    Environment        = "prod"
    Project            = "usora"
    ManagedBy          = "terraform"
    Compliance         = "SOC2"
    DataClassification = "PII"
    CostCenter         = "platform"
  }
}

module "vpc" {
  source = "../../modules/vpc"

  environment         = local.environment
  vpc_cidr            = "10.2.0.0/16"
  availability_zones  = local.azs
  single_nat_gateway  = false
  enable_nat_gateway  = true
  enable_flow_logs    = true
  enable_vpc_endpoints = true
  tags                = local.tags
}

resource "aws_shield_protection" "vpc" {
  name         = "usora-prod-vpc-protection"
  resource_arn = module.vpc.vpc_id

  tags = local.tags
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
      max_size       = 7
      desired_size   = 3
      capacity_type  = "SPOT"
      k8s_labels     = { "usora.io/node-type" = "gateway" }
    }
    gateway-ondemand = {
      instance_types = ["t3.large"]
      min_size       = 1
      max_size       = 3
      desired_size   = 2
      capacity_type  = "ON_DEMAND"
      k8s_labels     = { "usora.io/node-type" = "gateway" }
    }
    compute-spot = {
      instance_types = ["c6i.4xlarge"]
      min_size       = 2
      max_size       = 35
      desired_size   = 3
      capacity_type  = "SPOT"
      k8s_labels     = { "usora.io/node-type" = "compute" }
    }
    compute-ondemand = {
      instance_types = ["c6i.4xlarge"]
      min_size       = 1
      max_size       = 15
      desired_size   = 2
      capacity_type  = "ON_DEMAND"
      k8s_labels     = { "usora.io/node-type" = "compute" }
    }
    orchestration = {
      instance_types = ["m6i.2xlarge"]
      min_size       = 3
      max_size       = 10
      desired_size   = 3
      capacity_type  = "ON_DEMAND"
      k8s_labels     = { "usora.io/node-type" = "orchestration" }
    }
    data = {
      instance_types = ["r6i.2xlarge"]
      min_size       = 3
      max_size       = 10
      desired_size   = 3
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
  instance_class           = "db.r6g.2xlarge"
  multi_az                 = true
  replica_count            = 2
  allocated_storage        = 500
  iops                     = 10000
  storage_type             = "gp3"
  backup_retention_days    = 35
  deletion_protection      = true
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
  broker_count       = 3
  broker_instance_type = "kafka.m5.large"
  tags               = local.tags
}

module "redis" {
  source = "../../modules/elasticache"

  environment        = local.environment
  vpc_id             = module.vpc.vpc_id
  vpc_cidr           = module.vpc.vpc_cidr
  private_subnet_ids = module.vpc.private_subnet_ids
  node_type          = "cache.r6g.xlarge"
  num_shards         = 3
  replicas_per_shard = 2
  automatic_failover = true
  tags               = local.tags
}

resource "aws_acm_certificate" "api" {
  domain_name       = local.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = local.tags
}

resource "aws_route53_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.api.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  allow_overwrite = true
  name            = each.value.name
  records         = [each.value.record]
  ttl             = 60
  type            = each.value.type
  zone_id         = data.aws_route53_zone.main.zone_id
}

resource "aws_acm_certificate_validation" "api" {
  certificate_arn         = aws_acm_certificate.api.arn
  validation_record_fqdns = [for record in aws_route53_record.cert_validation : record.fqdn]
}

resource "aws_wafv2_web_acl" "regional" {
  name        = "usora-prod-regional-waf"
  description = "Regional WAF for ALB"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "AWS-AWSManagedRulesCommonRuleSet"
    priority = 0

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "AWSManagedRulesCommonRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWS-AWSManagedRulesSQLiRuleSet"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesSQLiRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "AWSManagedRulesSQLiRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWS-AWSManagedRulesKnownBadInputsRuleSet"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "AWSManagedRulesKnownBadInputsRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "RateLimit"
    priority = 3

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 10000
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "RateLimit"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "usora-prod-regional-waf"
    sampled_requests_enabled   = true
  }

  tags = local.tags
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = data.aws_lb.api.arn
  web_acl_arn  = aws_wafv2_web_acl.regional.arn
}

resource "aws_wafv2_web_acl" "cloudfront" {
  name        = "usora-prod-cloudfront-waf"
  description = "CloudFront WAF for API distribution"
  scope       = "CLOUDFRONT"

  default_action {
    allow {}
  }

  rule {
    name     = "AWS-AWSManagedRulesCommonRuleSet"
    priority = 0

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "CF-AWSManagedRulesCommonRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWS-AWSManagedRulesSQLiRuleSet"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesSQLiRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "CF-AWSManagedRulesSQLiRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWS-AWSManagedRulesKnownBadInputsRuleSet"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "CF-AWSManagedRulesKnownBadInputsRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "RateLimit"
    priority = 3

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 5000
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "CF-RateLimit"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "usora-prod-cloudfront-waf"
    sampled_requests_enabled   = true
  }

  tags = local.tags
}

resource "aws_cloudfront_distribution" "api" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "CloudFront distribution for USORA API"
  default_root_object = ""
  price_class         = "PriceClass_All"

  aliases = [local.domain_name]

  origin {
    domain_name = data.aws_lb.api.dns_name
    origin_id   = "ALBOrigin"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }

    custom_header {
      name  = "X-Origin-Verify"
      value = random_password.origin_header.result
    }
  }

  default_cache_behavior {
    allowed_methods  = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods   = ["GET", "HEAD", "OPTIONS"]
    target_origin_id = "ALBOrigin"

    forwarded_values {
      query_string = true
      headers      = ["Authorization", "Origin", "Referer", "Accept", "Content-Type"]

      cookies {
        forward = "all"
      }
    }

    viewer_protocol_policy = "redirect-to-https"
    min_ttl                = 0
    default_ttl            = 0
    max_ttl                = 0
    compress               = true
  }

  ordered_cache_behavior {
    path_pattern     = "/static/*"
    allowed_methods  = ["GET", "HEAD", "OPTIONS"]
    cached_methods   = ["GET", "HEAD", "OPTIONS"]
    target_origin_id = "ALBOrigin"

    forwarded_values {
      query_string = false
      headers      = []

      cookies {
        forward = "none"
      }
    }

    viewer_protocol_policy = "redirect-to-https"
    min_ttl                = 0
    default_ttl            = 86400
    max_ttl                = 604800
    compress               = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.api.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  web_acl_id = aws_wafv2_web_acl.cloudfront.arn

  tags = local.tags
}

resource "random_password" "origin_header" {
  length  = 32
  special = false
}

resource "aws_route53_record" "api" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = local.domain_name
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.api.domain_name
    zone_id                = aws_cloudfront_distribution.api.hosted_zone_id
    evaluate_target_health = true
  }
}

resource "aws_shield_protection" "cloudfront" {
  name         = "usora-prod-cloudfront-protection"
  resource_arn = aws_cloudfront_distribution.api.arn

  tags = local.tags
}
