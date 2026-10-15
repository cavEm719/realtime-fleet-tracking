#!/usr/bin/env bash
set -euo pipefail
project_root="$(git rev-parse --show-toplevel)"
cd "$project_root"
python3 - <<'PY'
"""Check tracked working files and the staged commit without displaying matched values."""
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys

config_path = Path('scripts/.public-safety-local.json')
config = json.loads(config_path.read_text()) if config_path.exists() else {}
public_ips = set(config.get('public_ips', []))
if os.environ.get('FLEET_PUBLIC_IP'):
    public_ips.add(os.environ['FLEET_PUBLIC_IP'])
local_vars = Path('infra/aws/terraform.tfvars')
if local_vars.exists():
    match = re.search(r'my_ip_cidr\s*=\s*"([^"/]+)/32"', local_vars.read_text())
    if match:
        public_ips.add(match.group(1))
if not public_ips:
    sys.exit('Public safety: configure your IP in ignored scripts/.public-safety-local.json or FLEET_PUBLIC_IP.')
for value in public_ips:
    if ipaddress.ip_address(value).version != 4:
        sys.exit('Public safety: operator IP must be IPv4.')

patterns = [
    ('AWS account in ARN', re.compile(rb'arn:aws[\w-]*:[^:\s]+:[^:\s]*:[0-9]{12}:')),
    ('AWS account field', re.compile(rb'"account(?:Id|_id)"\s*:\s*"?[0-9]{12}', re.I)),
    ('local home path', re.compile(b'/' + b'Users/')),
    ('assistant metadata', re.compile(b'co' + b'dex', re.I)),
    ('environment array dump', re.compile(rb'"(?:Env|environment)"\s*:\s*\[\s*"[A-Z][A-Z0-9_]*=', re.I)),
    ('full environment command', re.compile(rb'(?m)^\s*(?:printenv|env|set)\s*(?:[|;]|$)')),
    ('environment dump template', re.compile(rb'\{\{range\s+\.Config\.Env\}\}\{\{println')),
    ('process environment dump', re.compile(rb'(?:print|json\.dumps|write)\s*\(\s*(?:dict\()?os\.environ')),
    ('shell environment listing', re.compile(rb'(?m)(?:^[A-Z][A-Z0-9_]*=[^\n]*\n){6,}')),
]
patterns.extend(('operator public IP', re.compile(re.escape(v.encode()) + rb'(?![0-9])')) for v in public_ips)
violations = set()

def check(name, data, source):
    for label, pattern in patterns:
        if pattern.search(data) or pattern.search(name.encode()):
            violations.add((name, source, label))

entries = subprocess.check_output(['git', 'ls-files', '--stage', '-z']).split(b'\0')
blobs = {}
reader = subprocess.Popen(['git', 'cat-file', '--batch'], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
try:
    for entry in entries:
        if not entry:
            continue
        metadata, raw_name = entry.split(b'\t', 1)
        mode, digest, stage = metadata.split()
        name = os.fsdecode(raw_name)
        if stage != b'0':
            sys.exit('Public safety: resolve unmerged index entries first.')
        if mode == b'160000':
            sys.exit('Public safety: submodule contents require a separate tracked-file check.')
        if digest not in blobs:
            reader.stdin.write(digest + b'\n')
            reader.stdin.flush()
            header = reader.stdout.readline().split()
            size = int(header[2])
            blobs[digest] = reader.stdout.read(size)
            reader.stdout.read(1)
        check(name, blobs[digest], 'index')
        path = Path(name)
        if path.is_symlink():
            check(name, os.readlink(path).encode(), 'working tree')
        elif path.is_file():
            check(name, path.read_bytes(), 'working tree')
finally:
    reader.stdin.close()
    reader.wait()
if violations:
    for name, source, label in sorted(violations):
        print(f'{name}: {source}: {label}', file=sys.stderr)
    sys.exit(f'Public safety FAILED: {len(violations)} findings; matched values withheld.')
print('Public safety PASS: tracked working files and index checked.')
PY
