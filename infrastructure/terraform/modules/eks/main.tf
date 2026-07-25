terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

variable "environment" {
  type        = string
  description = "Environment name"
}

variable "vpc_id" {
  type        = string
  description = "VPC ID"
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Private subnet IDs for node groups"
}

variable "public_subnet_ids" {
  type        = list(string)
  description = "Public subnet IDs for load balancer endpoints"
  default     = []
}

variable "cluster_version" {
  type        = string
  description = "Kubernetes version"
  default     = "1.30"
}

variable "cluster_log_types" {
  type        = list(string)
  description = "Enabled cluster log types"
  default     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
}

variable "node_groups" {
  type = map(object({
    instance_types = list(string)
    min_size       = number
    max_size       = number
    desired_size   = number
    capacity_type  = string
    disk_size      = optional(number, 50)
    ami_type       = optional(string, "AL2_x86_64")
    taints = optional(list(object({
      key    = string
      value  = string
      effect = string
    })), [])
    k8s_labels = optional(map(string), {})
    tags       = optional(map(string), {})
  }))
  default     = {}
  description = "Managed node group configurations"
}

variable "tags" {
  type        = map(string)
  description = "Common tags applied to all resources"
  default     = {}
}

locals {
  name_prefix  = "usora-"
  cluster_name = "${local.name_prefix}${var.environment}-eks"

  common_tags = merge(var.tags, {
    Environment = var.environment
    ManagedBy   = "Terraform"
    Service     = "usora"
  })
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

resource "aws_kms_key" "eks" {
  description             = "EKS cluster encryption key - ${local.cluster_name}"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = local.common_tags
}

resource "aws_kms_alias" "eks" {
  name          = "alias/${local.cluster_name}"
  target_key_id = aws_kms_key.eks.key_id
}

resource "aws_iam_role" "cluster" {
  name = "${local.name_prefix}${var.environment}-eks-cluster-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "eks.amazonaws.com"
        }
      }
    ]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "cluster_AmazonEKSClusterPolicy" {
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSClusterPolicy"
  role       = aws_iam_role.cluster.name
}

resource "aws_iam_role_policy_attachment" "cluster_AmazonEKSVPCResourceController" {
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSVPCResourceController"
  role       = aws_iam_role.cluster.name
}

resource "aws_security_group" "cluster" {
  name        = "${local.name_prefix}${var.environment}-eks-cluster-sg"
  description = "Security group for EKS cluster control plane"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}${var.environment}-eks-cluster-sg"
  })
}

resource "aws_security_group_rule" "cluster_ingress_https" {
  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.cluster.id
}

resource "aws_eks_cluster" "main" {
  name     = local.cluster_name
  version  = var.cluster_version
  role_arn = aws_iam_role.cluster.arn

  vpc_config {
    subnet_ids              = distinct(concat(var.private_subnet_ids, var.public_subnet_ids))
    endpoint_private_access = true
    endpoint_public_access  = false
    public_access_cidrs     = []
    security_group_ids      = [aws_security_group.cluster.id]
  }

  encryption_config {
    provider {
      key_arn = aws_kms_key.eks.arn
    }
    resources = ["secrets"]
  }

  enabled_cluster_log_types = var.cluster_log_types

  kubernetes_network_config {
    service_ipv4_cidr = "172.20.0.0/16"
  }

  tags = local.common_tags

  depends_on = [
    aws_iam_role_policy_attachment.cluster_AmazonEKSClusterPolicy,
    aws_iam_role_policy_attachment.cluster_AmazonEKSVPCResourceController,
  ]
}

data "tls_certificate" "eks" {
  url = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "oidc" {
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.eks.certificates[0].sha1_fingerprint]
  url             = aws_eks_cluster.main.identity[0].oidc[0].issuer

  tags = local.common_tags
}

resource "aws_iam_role" "node" {
  name = "${local.name_prefix}${var.environment}-eks-node-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "node_AmazonEKSWorkerNodePolicy" {
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSWorkerNodePolicy"
  role       = aws_iam_role.node.name
}

resource "aws_iam_role_policy_attachment" "node_AmazonEKS_CNI_Policy" {
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKS_CNI_Policy"
  role       = aws_iam_role.node.name
}

resource "aws_iam_role_policy_attachment" "node_AmazonEC2ContainerRegistryReadOnly" {
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
  role       = aws_iam_role.node.name
}

resource "aws_iam_role_policy_attachment" "node_AmazonSSMManagedInstanceCore" {
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
  role       = aws_iam_role.node.name
}

resource "aws_launch_template" "node" {
  for_each = var.node_groups

  name        = "${local.name_prefix}${var.environment}-eks-${each.key}-lt"
  description = "Launch template for node group ${each.key}"

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size           = each.value.disk_size
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }

  monitoring {
    enabled = true
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  tag_specifications {
    resource_type = "instance"
    tags = merge(local.common_tags, {
      Name = "${local.name_prefix}${var.environment}-eks-${each.key}"
      "k8s.io/cluster-autoscaler/${local.cluster_name}" = "owned"
      "k8s.io/cluster-autoscaler/enabled"               = "true"
    }, each.value.tags)
  }

  tag_specifications {
    resource_type = "volume"
    tags = merge(local.common_tags, {
      Name = "${local.name_prefix}${var.environment}-eks-${each.key}-volume"
    }, each.value.tags)
  }

  tags = local.common_tags
}

resource "aws_eks_node_group" "main" {
  for_each = var.node_groups

  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${local.name_prefix}${var.environment}-eks-${each.key}"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.private_subnet_ids

  ami_type       = each.value.ami_type
  capacity_type  = each.value.capacity_type
  disk_size      = each.value.disk_size
  instance_types = each.value.instance_types

  scaling_config {
    desired_size = each.value.desired_size
    min_size     = each.value.min_size
    max_size     = each.value.max_size
  }

  dynamic "taint" {
    for_each = each.value.taints
    content {
      key    = taint.value.key
      value  = taint.value.value
      effect = taint.value.effect
    }
  }

  labels = merge({
    "usora.io/node-group"   = each.key
    "usora.io/environment"  = var.environment
  }, each.value.k8s_labels)

  launch_template {
    id      = aws_launch_template.node[each.key].id
    version = aws_launch_template.node[each.key].latest_version
  }

  update_config {
    max_unavailable = 1
  }

  lifecycle {
    create_before_destroy = true
    ignore_changes = [
      scaling_config[0].desired_size,
    ]
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}${var.environment}-eks-${each.key}"
  }, each.value.tags)

  depends_on = [
    aws_iam_role_policy_attachment.node_AmazonEKSWorkerNodePolicy,
    aws_iam_role_policy_attachment.node_AmazonEKS_CNI_Policy,
    aws_iam_role_policy_attachment.node_AmazonEC2ContainerRegistryReadOnly,
  ]
}

data "aws_iam_policy_document" "cluster_autoscaler_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    effect  = "Allow"

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.oidc.url, "https://", "")}:sub"
      values   = ["system:serviceaccount:kube-system:cluster-autoscaler"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.oidc.url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.oidc.arn]
    }
  }
}

resource "aws_iam_role" "cluster_autoscaler" {
  name               = "${local.name_prefix}${var.environment}-cluster-autoscaler"
  assume_role_policy = data.aws_iam_policy_document.cluster_autoscaler_assume.json

  tags = local.common_tags
}

data "aws_iam_policy_document" "cluster_autoscaler" {
  statement {
    actions = [
      "autoscaling:DescribeAutoScalingGroups",
      "autoscaling:DescribeAutoScalingInstances",
      "autoscaling:DescribeLaunchConfigurations",
      "autoscaling:DescribeScalingActivities",
      "autoscaling:DescribeTags",
      "ec2:DescribeInstanceTypes",
      "ec2:DescribeLaunchTemplateVersions",
    ]
    effect    = "Allow"
    resources = ["*"]
  }

  statement {
    actions = [
      "autoscaling:SetDesiredCapacity",
      "autoscaling:TerminateInstanceInAutoScalingGroup",
    ]
    effect    = "Allow"
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "autoscaling:ResourceTag/k8s.io/cluster-autoscaler/${local.cluster_name}"
      values   = ["owned"]
    }
  }
}

resource "aws_iam_policy" "cluster_autoscaler" {
  name        = "${local.name_prefix}${var.environment}-cluster-autoscaler"
  description = "Policy for cluster autoscaler"
  policy      = data.aws_iam_policy_document.cluster_autoscaler.json

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "cluster_autoscaler" {
  policy_arn = aws_iam_policy.cluster_autoscaler.arn
  role       = aws_iam_role.cluster_autoscaler.name
}

data "aws_iam_policy_document" "ebs_csi_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    effect  = "Allow"

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.oidc.url, "https://", "")}:sub"
      values   = ["system:serviceaccount:kube-system:ebs-csi-controller-sa"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.oidc.url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.oidc.arn]
    }
  }
}

resource "aws_iam_role" "ebs_csi" {
  name               = "${local.name_prefix}${var.environment}-ebs-csi"
  assume_role_policy = data.aws_iam_policy_document.ebs_csi_assume.json

  tags = local.common_tags
}

data "aws_iam_policy_document" "ebs_csi" {
  statement {
    actions = [
      "ec2:CreateSnapshot",
      "ec2:AttachVolume",
      "ec2:DetachVolume",
      "ec2:ModifyVolume",
      "ec2:DescribeAvailabilityZones",
      "ec2:DescribeInstances",
      "ec2:DescribeSnapshots",
      "ec2:DescribeTags",
      "ec2:DescribeVolumes",
      "ec2:DescribeVolumesModifications",
    ]
    effect    = "Allow"
    resources = ["*"]
  }

  statement {
    actions = [
      "ec2:CreateTags",
      "ec2:DeleteTags",
    ]
    effect    = "Allow"
    resources = [
      "arn:${data.aws_partition.current.partition}:ec2:*:*:volume/*",
      "arn:${data.aws_partition.current.partition}:ec2:*:*:snapshot/*",
    ]
  }

  statement {
    actions   = ["ec2:CreateVolume"]
    effect    = "Allow"
    resources = ["*"]
    condition {
      test     = "StringLike"
      variable = "aws:RequestTag/ebs.csi.aws.com/cluster"
      values   = ["true"]
    }
  }

  statement {
    actions   = ["ec2:DeleteVolume"]
    effect    = "Allow"
    resources = ["*"]
    condition {
      test     = "StringLike"
      variable = "aws:ResourceTag/ebs.csi.aws.com/cluster"
      values   = ["true"]
    }
  }
}

resource "aws_iam_policy" "ebs_csi" {
  name        = "${local.name_prefix}${var.environment}-ebs-csi"
  description = "Policy for EBS CSI driver"
  policy      = data.aws_iam_policy_document.ebs_csi.json

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  policy_arn = aws_iam_policy.ebs_csi.arn
  role       = aws_iam_role.ebs_csi.name
}

data "aws_eks_cluster_auth" "main" {
  name = aws_eks_cluster.main.name
}

provider "kubernetes" {
  host                   = aws_eks_cluster.main.endpoint
  cluster_ca_certificate = base64decode(aws_eks_cluster.main.certificate_authority[0].data)
  token                  = data.aws_eks_cluster_auth.main.token
}

resource "kubernetes_config_map_v1" "aws_auth" {
  metadata {
    name      = "aws-auth"
    namespace = "kube-system"
  }

  data = {
    mapRoles = jsonencode(concat(
      [
        {
          rolearn  = aws_iam_role.node.arn
          username = "system:node:{{EC2PrivateDNSName}}"
          groups   = ["system:bootstrappers", "system:nodes"]
        },
      ],
      [
        {
          rolearn  = aws_iam_role.cluster_autoscaler.arn
          username = "cluster-autoscaler"
          groups   = ["system:masters"]
        },
      ],
      [
        {
          rolearn  = aws_iam_role.ebs_csi.arn
          username = "ebs-csi-controller-sa"
          groups   = ["ebs-csi"]
        },
      ]
    ))
  }

  depends_on = [aws_eks_cluster.main]
}

output "cluster_endpoint" {
  value = aws_eks_cluster.main.endpoint
}

output "cluster_ca_cert" {
  value = aws_eks_cluster.main.certificate_authority[0].data
}

output "cluster_name" {
  value = aws_eks_cluster.main.name
}

output "cluster_arn" {
  value = aws_eks_cluster.main.arn
}

output "cluster_security_group_id" {
  value = aws_security_group.cluster.id
}

output "oidc_provider_arn" {
  value = aws_iam_openid_connect_provider.oidc.arn
}

output "oidc_provider_url" {
  value = aws_iam_openid_connect_provider.oidc.url
}

output "node_role_arn" {
  value = aws_iam_role.node.arn
}

output "node_role_arns" {
  value = tomap({
    for k, ng in aws_eks_node_group.main : k => aws_iam_role.node.arn
  })
}

output "cluster_autoscaler_role_arn" {
  value = aws_iam_role.cluster_autoscaler.arn
}

output "ebs_csi_role_arn" {
  value = aws_iam_role.ebs_csi.arn
}

output "kms_key_arn" {
  value = aws_kms_key.eks.arn
}

output "node_group_names" {
  value = tomap({
    for k, ng in aws_eks_node_group.main : k => ng.node_group_name
  })
}
