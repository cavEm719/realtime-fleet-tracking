#!/usr/bin/env bash
# Operator-run only: pushes images to repositories already created by Terraform.
set -euo pipefail
dry_run=false
if [[ "${1:-}" == --dry-run ]]; then
  dry_run=true
  shift
fi
inventory=${1:?inventory JSON required}
tag=${2:?unique immutable image tag required}
if [[ "$dry_run" == true ]]; then
  python3 - "$inventory" "$tag" <<'PYDRY'
import json, subprocess, sys
values=json.load(open(sys.argv[1]))
assert values["region"] == "us-east-1"
assert set(values["images"]) == {"api", "copilot"}
for module, repository in sorted(values["images"].items()):
    local=f"fleet-{module}:{sys.argv[2]}"
    arch=subprocess.check_output(["docker", "image", "inspect", "--format", "{{.Architecture}}", local],text=True).strip()
    assert arch == "arm64"
    print(json.dumps({"localImage":local,"push":repository+":"+sys.argv[2],"dryRun":True}))
PYDRY
  exit 0
fi
python3 - "$inventory" <<'PYVALIDATE'
import json,sys
value=json.load(open(sys.argv[1]))
assert not value.get("planned"), "Planned inventory may only be used for dry run"
assert value["region"] == "us-east-1"
PYVALIDATE
region=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["region"])' "$inventory")
registry=$(python3 -c 'import json,sys; print(next(iter(json.load(open(sys.argv[1]))["images"].values())).split("/")[0])' "$inventory")
# A short-lived credential helper asks the current operator session on demand.
# The ECR password is passed directly to Docker, never stored in config.json.
original_docker_config="${DOCKER_CONFIG:-$HOME/.docker}"
export DOCKER_HOST="${DOCKER_HOST:-$(docker context inspect --format '{{.Endpoints.docker.Host}}')}"
operator_docker_config=$(mktemp -d)
trap 'rm -rf "$operator_docker_config"' EXIT
export DOCKER_CONFIG="$operator_docker_config"
export PATH="$operator_docker_config:$PATH"
export FLEET_ECR_REGION="$region"
cat > "$operator_docker_config/docker-credential-fleet-operator" <<'PYHELPER'
#!/usr/bin/env python3
import json
import os
import subprocess
import sys

if sys.argv[1] != "get":
    raise SystemExit("Only on-demand get is supported")
server = sys.stdin.read().strip()
result = subprocess.run(
    ["aws", "ecr", "get-login-password", "--region", os.environ["FLEET_ECR_REGION"]],
    check=True, capture_output=True, text=True,
)
print(json.dumps({"ServerURL": server, "Username": "AWS", "Secret": result.stdout.strip()}))
PYHELPER
chmod 700 "$operator_docker_config/docker-credential-fleet-operator"
python3 - "$registry" "$original_docker_config" "$operator_docker_config" <<'PYCONFIG'
import json
import sys
from pathlib import Path
registry, original, temporary = sys.argv[1:]
source = Path(original) / "config.json"
config = json.loads(source.read_text()) if source.exists() else {}
plugins = config.get("cliPluginsExtraDirs", []) + [str(Path(original) / "cli-plugins")]
Path(temporary, "config.json").write_text(json.dumps({
    "credHelpers": {registry: "fleet-operator"},
    "cliPluginsExtraDirs": plugins,
}))
PYCONFIG
docker buildx version >/dev/null
for module in $(python3 -c 'import json,sys; print(" ".join(sorted(json.load(open(sys.argv[1]))["images"])))' "$inventory"); do
  repository=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["images"][sys.argv[2]])' "$inventory" "$module")
  local_image="fleet-$module:$tag"
  architecture=$(docker image inspect --format '{{.Architecture}}' "$local_image")
  test "$architecture" = arm64
  docker tag "$local_image" "$repository:$tag"
  docker push "$repository:$tag"
done
