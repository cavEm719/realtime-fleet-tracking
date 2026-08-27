variable "fleet_profile" {
  description = "benchmark preserves the full streaming layout; evaluation provisions only API/copilot dependencies and one runner."
  type        = string
  default     = "benchmark"
  validation {
    condition     = contains(["benchmark", "evaluation"], var.fleet_profile)
    error_message = "fleet_profile must be benchmark or evaluation."
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}
variable "availability_zone" {
  type    = string
  default = "us-east-1a"
}
variable "my_ip_cidr" {
  description = "Operator public IPv4 /32, never 0.0.0.0/0."
  type        = string
  validation {
    condition     = can(cidrnetmask(var.my_ip_cidr)) && endswith(var.my_ip_cidr, "/32")
    error_message = "Use your current public IPv4 address followed by /32."
  }
}
variable "ssh_public_key" {
  description = "Public OpenSSH key only; private keys stay on the operator's machine."
  type        = string
}
variable "processors" {
  type    = number
  default = 4
  validation {
    condition     = var.processors >= 1 && var.processors <= 4 && floor(var.processors) == var.processors
    error_message = "processors must be an integer from 1 through 4."
  }
}
variable "gateways" {
  type    = number
  default = 2
  validation {
    condition     = contains([1, 2], var.gateways)
    error_message = "gateways must be 1 or 2."
  }
}
variable "load_generators" {
  type    = number
  default = 2
  validation {
    condition     = var.load_generators >= 1 && var.load_generators <= 8 && floor(var.load_generators) == var.load_generators
    error_message = "load_generators must be an integer from 1 through 8."
  }
}
variable "instance_types" {
  type = object({ kafka = string, redis = string, mysql = string, api = string, copilot = string, processor = string, gateway = string, nginx = string, loadgen = string })
  default = {
    kafka   = "m7g.large", redis = "m7g.large", mysql = "r7g.large", api = "c7g.large",
    copilot = "m7g.large", processor = "c7g.large", gateway = "c7g.large", nginx = "c7g.large", loadgen = "c7g.xlarge"
  }
  validation {
    condition     = alltrue([for v in values(var.instance_types) : can(regex("^[cmrt][0-9]+g[nad]?\\.", v))])
    error_message = "Choose ARM/Graviton instance families; the AMI and images are arm64."
  }
}
variable "bedrock_model_arns" {
  description = "Explicit in-region model/inference-profile ARNs permitted for copilot and evaluator. No wildcard defaults."
  type        = list(string)
  default     = []
}
variable "root_volume_gb" {
  type    = number
  default = 40
}
