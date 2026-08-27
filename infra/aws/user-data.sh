#!/bin/bash
set -euo pipefail
dnf install -y docker chrony sysstat python3 python3-pip git
# The selected Amazon Linux 2023 AMI already includes AWS CLI v2.
command -v aws >/dev/null
systemctl enable --now docker chronyd
usermod -aG docker ec2-user
# Amazon Time Sync is link-local and needs neither a public NTP server nor ingress.
if ! grep -q '169.254.169.123' /etc/chrony.conf; then
  echo 'server 169.254.169.123 prefer iburst minpoll 4 maxpoll 4' >> /etc/chrony.conf
  systemctl restart chronyd
fi
mkdir -p /opt/fleet
chown ec2-user:ec2-user /opt/fleet
