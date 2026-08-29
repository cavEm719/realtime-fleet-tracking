#!/usr/bin/env python3
"""Build and start the evaluation ARM images without AWS calls or image pushes."""
import argparse
import hashlib
import json
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def verify_artifact(module, image):
    """Bind the startup check to the current packaged artifact, not an older tag."""
    artifact = ROOT / module / "target" / f"{module}-0.1.0-SNAPSHOT-exec.jar"
    expected = hashlib.sha256(artifact.read_bytes()).hexdigest()
    container = run("docker", "create", image, capture_output=True, text=True).stdout.strip()
    try:
        with tempfile.TemporaryDirectory(prefix="fleet-artifact-check-") as temp:
            copied = Path(temp) / "service.jar"
            run("docker", "cp", f"{container}:/app/service.jar", str(copied))
            actual = hashlib.sha256(copied.read_bytes()).hexdigest()
    finally:
        run("docker", "rm", container, capture_output=True, text=True)
    if actual != expected:
        raise RuntimeError(f"{module} image does not contain the current packaged JAR")
    return expected


def verify(tag, output):
    output.mkdir(parents=True, exist_ok=False)
    config = Path.home() / ".docker/config.json"
    before = (
        hashlib.sha256(config.read_bytes()).hexdigest() if config.exists() else None
    )
    services = {
        "mysql": {
            "image": "mysql:8.0.46",
            "environment": {
                "MYSQL_DATABASE": "fleet",
                "MYSQL_USER": "fleet",
                "MYSQL_PASSWORD": "local-smoke",
                "MYSQL_ROOT_PASSWORD": "local-smoke",
            },
        },
        "redis": {"image": "redis:7.4.9"},
        "kafka": {
            "image": "apache/kafka:3.9.1",
            "environment": {
                "KAFKA_NODE_ID": "1",
                "KAFKA_PROCESS_ROLES": "broker,controller",
                "KAFKA_LISTENERS": "PLAINTEXT://:9092,CONTROLLER://:9093",
                "KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://kafka:9092",
                "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
                "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@localhost:9093",
                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
                "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
            },
        },
        "api": {
            "image": f"fleet-api:{tag}",
            "environment": {
                "MYSQL_URL": "jdbc:mysql://mysql:3306/fleet",
                "MYSQL_USER": "fleet",
                "MYSQL_PASSWORD": "local-smoke",
                "REDIS_HOST": "redis",
                "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
                "TRACING_ENABLED": "false",
            },
        },
        "copilot": {
            "image": f"fleet-copilot:{tag}",
            "environment": {
                "REDIS_HOST": "redis",
                "API_GRPC_TARGET": "api:9095",
                "COPILOT_PROVIDER": "bedrock",
                "TRACING_ENABLED": "false",
            },
        },
    }
    for service in services.values():
        service["platform"] = "linux/arm64"
    with tempfile.TemporaryDirectory(prefix="fleet-local-images-") as temp:
        compose = Path(temp) / "compose.json"
        compose.write_text(json.dumps({"services": services}))
        command = [
            "docker",
            "compose",
            "-p",
            "fleet-eval-image-check",
            "-f",
            str(compose),
        ]
        try:
            for module in ("api", "copilot"):
                with (output / f"build-{module}.log").open("w") as log:
                    run(
                        "docker",
                        "buildx",
                        "build",
                        "--platform",
                        "linux/arm64",
                        "--build-arg",
                        f"MODULE={module}",
                        "--tag",
                        f"fleet-{module}:{tag}",
                        "--load",
                        ".",
                        cwd=ROOT,
                        stdout=log,
                        stderr=subprocess.STDOUT,
                    )
            run(*command, "up", "-d", "mysql", "redis", "kafka")
            probes = {
                "mysql": [
                    "sh",
                    "-c",
                    "MYSQL_PWD=local-smoke mysqladmin ping -h127.0.0.1 -uroot --silent",
                ],
                "redis": ["redis-cli", "ping"],
                "kafka": [
                    "/opt/kafka/bin/kafka-topics.sh",
                    "--bootstrap-server",
                    "localhost:9092",
                    "--list",
                ],
                "api": ["curl", "-fsS", "http://localhost:8080/actuator/health"],
                "copilot": ["curl", "-fsS", "http://localhost:8083/actuator/health"],
            }
            verified = {}
            for name, probe in probes.items():
                if name in ("api", "copilot"):
                    run(*command, "up", "-d", name)
                deadline = time.monotonic() + 180
                while True:
                    result = subprocess.run(
                        [*command, "exec", "-T", name, *probe],
                        capture_output=True,
                        text=True,
                    )
                    if result.returncode == 0:
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError(f"Local {name} startup failed")
                    time.sleep(2)
                image = services[name]["image"]
                arch = run(
                    "docker",
                    "image",
                    "inspect",
                    "--format",
                    "{{.Architecture}}",
                    image,
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                if arch != "arm64":
                    raise RuntimeError(f"{name} architecture is {arch}")
                verified[name] = {
                    "image": image,
                    "architecture": arch,
                    "probe": result.stdout.strip(),
                }
                if name in ("api", "copilot"):
                    verified[name]["artifactSha256"] = verify_artifact(name, image)
            (output / "verified.json").write_text(json.dumps(verified, indent=2) + "\n")
        finally:
            with (output / "startup.log").open("w") as log:
                subprocess.run(
                    [*command, "logs", "--no-color"],
                    stdout=log,
                    stderr=subprocess.STDOUT,
                )
            run(*command, "down", "--volumes", "--remove-orphans")
            after = (
                hashlib.sha256(config.read_bytes()).hexdigest()
                if config.exists()
                else None
            )
            if before != after:
                raise RuntimeError("User Docker config changed")
            (output / "docker-config-check.json").write_text(
                json.dumps({"unchanged": before == after})
            )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    verify(args.tag, args.output)
