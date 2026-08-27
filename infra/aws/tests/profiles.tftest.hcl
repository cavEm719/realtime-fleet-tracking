mock_provider "aws" {
  mock_data "aws_ssm_parameter" {
    defaults = { value = "ami-0123456789abcdef0" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "<ACCOUNT_ID>" }
  }
}
variables {
  my_ip_cidr = "192.0.2.1/32"
  ssh_public_key = "test-public-key"
}
run "evaluation_isolated" {
  command = plan
  variables { fleet_profile = "evaluation" }
  assert {
    condition = toset(keys(local.hosts)) == toset(["api", "copilot", "mysql", "redis", "kafka", "loadgen-1"])
    error_message = "Evaluation must have only six required hosts."
  }
  assert {
    condition = local.images == toset(["api", "copilot"])
    error_message = "Evaluation must not create streaming image repositories."
  }
  assert {
    condition = alltrue([for rule in aws_security_group.bench.ingress : rule.from_port != 8082])
    error_message = "Evaluation must not expose a gateway port."
  }
}
run "benchmark_unchanged" {
  command = plan
  variables { fleet_profile = "benchmark" }
  assert {
    condition = length(local.hosts) == 14 && contains(keys(local.hosts), "nginx") && contains(keys(local.hosts), "processor-4") && contains(keys(local.hosts), "gateway-2")
    error_message = "Benchmark defaults must preserve the fourteen-host layout."
  }
}

run "repository_step" {
  command = plan
  variables { fleet_profile = "evaluation" }
  plan_options { target = [aws_ecr_repository.service] }
  assert {
    condition = toset(keys(aws_ecr_repository.service)) == toset(["api", "copilot"])
    error_message = "Repository stage must target exactly two evaluation repositories."
  }
}
run "remaining_step" {
  command = plan
  variables { fleet_profile = "evaluation" }
  assert {
    condition = length(aws_instance.host) == 6
    error_message = "Evaluation remainder must have six hosts."
  }
}
