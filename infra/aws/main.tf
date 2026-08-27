data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_ssm_parameter" "ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}
locals {
  evaluation = var.fleet_profile == "evaluation"
  fixed = merge(
    { kafka = "kafka", redis = "redis", mysql = "mysql", api = "api", copilot = "copilot" },
    local.evaluation ? {} : { nginx = "nginx" }
  )
  hosts = merge(local.fixed,
    { for i in range(local.evaluation ? 0 : var.processors) : "processor-${i + 1}" => "processor" },
    { for i in range(local.evaluation ? 0 : var.gateways) : "gateway-${i + 1}" => "gateway" },
    { for i in range(local.evaluation ? 1 : var.load_generators) : "loadgen-${i + 1}" => "loadgen" }
  )
  images = local.evaluation ? toset(["api", "copilot"]) : toset(["api", "copilot", "processor", "gateway", "replayer", "loadtest"])
}
resource "aws_vpc" "bench" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "fleet-bench" }
}
resource "aws_subnet" "bench" {
  vpc_id                  = aws_vpc.bench.id
  cidr_block              = "10.42.1.0/24"
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = true
  tags                    = { Name = "fleet-bench" }
}
resource "aws_internet_gateway" "bench" {
  vpc_id = aws_vpc.bench.id
  tags   = { Name = "fleet-bench" }
}
resource "aws_route_table" "bench" {
  vpc_id = aws_vpc.bench.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.bench.id
  }
  tags = { Name = "fleet-bench" }
}
resource "aws_route_table_association" "bench" {
  subnet_id      = aws_subnet.bench.id
  route_table_id = aws_route_table.bench.id
}
resource "aws_security_group" "bench" {
  name_prefix = "fleet-bench-"
  vpc_id      = aws_vpc.bench.id
  ingress {
    description = "Internal service traffic only from this VPC"
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.bench.cidr_block]
  }
  dynamic "ingress" {
    for_each = local.evaluation ? toset([22, 8080]) : toset([22, 8080, 8082])
    content {
      description = "Operator SSH/API/gateway access"
      from_port   = ingress.value
      to_port     = ingress.value
      protocol    = "tcp"
      cidr_blocks = [var.my_ip_cidr]
    }
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "fleet-bench" }
}
resource "aws_key_pair" "bench" {
  key_name_prefix = "fleet-bench-"
  public_key      = var.ssh_public_key
}
resource "aws_ecr_repository" "service" {
  for_each             = local.images
  name                 = "fleet-bench/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true # Ephemeral benchmark: destroy also removes pushed images.
  image_scanning_configuration { scan_on_push = true }
}
resource "aws_iam_role" "host" {
  name_prefix        = "fleet-bench-"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" } }] })
}
resource "aws_iam_role_policy" "host" {
  role = aws_iam_role.host.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Effect = "Allow", Action = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"], Resource = [for repo in aws_ecr_repository.service : repo.arn] }
    ], length(var.bedrock_model_arns) == 0 ? [] : [{ Effect = "Allow", Action = ["bedrock:InvokeModel"], Resource = var.bedrock_model_arns }])
  })
}
resource "aws_iam_instance_profile" "host" {
  name_prefix = "fleet-bench-"
  role        = aws_iam_role.host.name
}
resource "aws_instance" "host" {
  for_each               = local.hosts
  ami                    = data.aws_ssm_parameter.ami.value
  instance_type          = var.instance_types[each.value]
  subnet_id              = aws_subnet.bench.id
  vpc_security_group_ids = [aws_security_group.bench.id]
  key_name               = aws_key_pair.bench.key_name
  iam_instance_profile   = aws_iam_instance_profile.host.name
  user_data              = file("${path.module}/user-data.sh")
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2 # Container instance-role credentials with IMDSv2.
  }
  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_gb
    encrypted             = true
    delete_on_termination = true
    tags                  = { project = "fleet-bench" }
  }
  tags       = { Name = "fleet-bench-${each.key}", role = each.value }
  depends_on = [aws_route_table_association.bench]
}
