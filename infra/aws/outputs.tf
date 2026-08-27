output "inventory" {
  value = {
    project = "fleet-bench"
    region  = var.region
    hosts = { for name, host in aws_instance.host : name => {
      public_ip = host.public_ip, private_ip = host.private_ip, instance_type = host.instance_type, role = local.hosts[name]
    } }
    images = { for name, repo in aws_ecr_repository.service : name => repo.repository_url }
  }
}
output "next_commands" {
  value = [
    "terraform -chdir=infra/aws output -json inventory > infra/aws/inventory.json",
    "infra/aws/scripts/push-images.sh infra/aws/inventory.json <unique-git-tag>",
    "python3 infra/aws/scripts/deploy.py --inventory infra/aws/inventory.json --key <private-key-path> --tag <unique-git-tag> --password-file <demo-password-file>",
    "See infra/aws/README.md for readiness checks, benchmark trials, evaluation and cleanup."
  ]
}
