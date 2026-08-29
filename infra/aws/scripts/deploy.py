#!/usr/bin/env python3
"""Operator-run deployment to existing hosts. Does not create AWS resources."""
import argparse
import json
import shlex
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def ssh(host, key, command, **options):
    options.setdefault("timeout", 900)
    return subprocess.run(
        [
            "ssh",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "-o",
            "ConnectTimeout=10",
            "-o",
            "ServerAliveInterval=10",
            "-o",
            "ServerAliveCountMax=3",
            "-i",
            str(key),
            "ec2-user@" + host["public_ip"],
            command,
        ],
        check=True,
        **options,
    )


def mysql_readiness_command():
    probe = shlex.join(
        [
            "sudo",
            "docker",
            "exec",
            "fleet",
            "sh",
            "-c",
            "MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysqladmin ping -h127.0.0.1 -uroot --silent",
        ]
    )
    return shlex.join(
        ["timeout", "180", "bash", "-c", f"until {probe}; do sleep 2; done"]
    )


def deploy(
    inventory,
    key,
    tag,
    password,
    model,
    evaluation=False,
    fixed_now="",
    bedrock_region="us-east-1",
    dry_run=False,
):
    if bedrock_region not in ("us-east-1", "us-east-2", "us-west-2"):
        raise ValueError("Bedrock inference must stay in an approved US region")
    if inventory.get("planned") and not dry_run:
        raise ValueError("Planned inventory may only be used for dry run")
    if (
        inventory.get("project") != "fleet-bench"
        or inventory.get("region") != "us-east-1"
    ):
        raise ValueError("Only fleet-bench in us-east-1 is allowed")
    if evaluation:
        expected = {"api", "copilot", "kafka", "mysql", "redis", "loadgen-1"}
        if set(inventory["hosts"]) != expected or set(inventory["images"]) != {
            "api",
            "copilot",
        }:
            raise ValueError("Unexpected evaluation hosts or images")

    def execute(host, key, command, **options):
        if dry_run:
            print(
                json.dumps(
                    {
                        "hostRole": host["role"],
                        "command": command,
                        "dryRun": True,
                        "inputBytes": len(options.get("input", b"")),
                    }
                )
            )
            return
        return ssh(host, key, command, **options)

    hosts = inventory["hosts"]
    ip = lambda name: hosts[name]["private_ip"]
    region = inventory["region"]
    images = {name: url + ":" + tag for name, url in inventory["images"].items()}
    registry = next(iter(images.values())).split("/")[0]
    shared = {
        "KAFKA_BOOTSTRAP_SERVERS": ip("kafka") + ":9092",
        "REDIS_HOST": ip("redis"),
        "MYSQL_URL": "jdbc:mysql://" + ip("mysql") + ":3306/fleet",
        "MYSQL_DATABASE": "fleet",
        "MYSQL_USER": "fleet",
        "MYSQL_PASSWORD": password,
        "API_GRPC_TARGET": ip("api") + ":9095",
        "TRACING_ENABLED": "false",
        "AWS_REGION": region,
        "GEOFENCE_MODE": "hysteresis",
    }
    archive = subprocess.run(
        [
            "tar",
            "czf",
            "-",
            "--exclude=.terraform",
            "--exclude=*.tfstate*",
            "--exclude=*.tfvars",
            "--exclude=inventory.json",
            "--exclude=deployment.json",
            "--exclude=*.local.json",
            "--exclude=plan*",
            "--exclude=results",
            "--exclude=__pycache__",
            "infra/aws",
            "bench",
            "copilot-eval",
        ],
        cwd=ROOT,
        capture_output=True,
        check=True,
    ).stdout
    for name, host in hosts.items():
        execute(
            host,
            key,
            "cloud-init status --wait >/dev/null; mkdir -p /opt/fleet/source /opt/fleet/data /opt/fleet/results; tar xzf - -C /opt/fleet/source",
            input=archive,
        )
        env = dict(shared)
        role = host["role"]
        if role == "api":
            env.update(API_PORT="8080", API_GRPC_PORT="9095")
        if role == "copilot":
            env.update(
                AWS_REGION=bedrock_region,
                COPILOT_PORT="8083",
                COPILOT_PROVIDER="bedrock",
                COPILOT_MODEL=model,
                COPILOT_EVALUATION_ENABLED=str(evaluation).lower(),
                COPILOT_FIXED_NOW=fixed_now,
            )
        if role == "processor":
            env.update(PROCESSOR_PORT="8081", SPRING_KAFKA_CONSUMER_CLIENT_ID=name)
        if role == "gateway":
            env["GATEWAY_PORT"] = "8082"
        if role == "mysql":
            env.update(MYSQL_ROOT_PASSWORD=password)
        content = "\n".join(f"{k}={v}" for k, v in env.items()) + "\n"
        execute(
            host, key, "umask 077; cat > /opt/fleet/service.env", input=content.encode()
        )
        # Docker asks the instance role for a token on demand; no login token is stored.
        execute(
            host,
            key,
            "sudo install -m 0755 /opt/fleet/source/infra/aws/scripts/ecr-credential-helper.py "
            "/usr/local/bin/docker-credential-fleet-ecr; sudo mkdir -p /root/.docker; "
            "sudo tee /root/.docker/config.json >/dev/null",
            input=(
                json.dumps({"credHelpers": {registry: "fleet-ecr"}}) + "\n"
            ).encode(),
        )
    # Start infrastructure first; API owns schema migration.
    commands = {
        "mysql": "docker run -d --name fleet --restart unless-stopped --network host --env-file /opt/fleet/service.env -v fleet-mysql:/var/lib/mysql mysql:8.0.46",
        "redis": "docker run -d --name fleet --restart unless-stopped --network host redis:7.4.9 redis-server --save '' --appendonly no",
        "kafka": "docker run -d --name fleet --restart unless-stopped --network host "
        "-e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller "
        "-e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 "
        f"-e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://{ip('kafka')}:9092 "
        "-e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 "
        "-e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT "
        "-e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 "
        "-e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_HEAP_OPTS='-Xms1g -Xmx1g' "
        "apache/kafka:3.9.1",
    }
    for name in ["mysql", "redis", "kafka"]:
        execute(
            hosts[name],
            key,
            "sudo docker rm -f fleet >/dev/null 2>&1 || true; sudo " + commands[name],
        )
    execute(
        hosts["mysql"],
        key,
        mysql_readiness_command(),
    )
    execute(
        hosts["kafka"],
        key,
        "timeout 120 bash -c 'until sudo docker exec fleet /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list; do sleep 2; done'",
    )
    order = (
        ["api", "copilot"]
        + sorted(n for n in hosts if n.startswith("processor-"))
        + sorted(n for n in hosts if n.startswith("gateway-"))
    )
    for name in order:
        host = hosts[name]
        role = host["role"]
        arguments = []
        if role == "processor":
            arguments = ["--fence-cache.group-id=fleet-fences-" + name]
        if role == "gateway":
            arguments = ["--gateway.event-group-id=fleet-events-" + name]
        cmd = [
            "sudo",
            "docker",
            "run",
            "-d",
            "--name",
            "fleet",
            "--restart",
            "unless-stopped",
            "--network",
            "host",
            "--env-file",
            "/opt/fleet/service.env",
            "--memory",
            "2g",
            "--entrypoint",
            "java",
            images[role],
            "-Xms256m",
            "-Xmx1g",
            "-jar",
            "/app/service.jar",
            *arguments,
        ]
        execute(
            host,
            key,
            "sudo docker rm -f fleet >/dev/null 2>&1 || true; " + shlex.join(cmd),
        )
        port = {"api": 8080, "copilot": 8083, "processor": 8081, "gateway": 8082}[role]
        execute(
            host,
            key,
            f"timeout 180 bash -c 'until curl -fsS http://localhost:{port}/actuator/health >/dev/null; do sleep 2; done'",
        )
    if "nginx" in hosts:
        update_nginx(
            inventory, key, len([n for n in hosts if n.startswith("gateway-")])
        )
    for name, host in hosts.items():
        if host["role"] == "loadgen":
            execute(
                host,
                key,
                "sudo chown 10001:10001 /opt/fleet/results; sudo chmod 0777 /opt/fleet/results; python3 -m pip install --user boto3",
            )
    output = {
        "tag": tag,
        "model": model,
        "region": region,
        "jvm": "-Xms256m -Xmx1g",
        "service_memory": "2g",
        "tracing_enabled": False,
    }
    if not dry_run:
        (ROOT / "infra/aws/deployment.json").write_text(
            json.dumps(output, indent=2) + "\n"
        )


def update_nginx(inventory, key, count):
    servers = [
        f"server {inventory['hosts'][f'gateway-{i}']['private_ip']}:8082;"
        for i in range(1, count + 1)
    ]
    config = """worker_processes auto;
events { worker_connections 20000; }
http {
  upstream gateways { zone gateways 64k; least_conn; SERVERS }
  server { listen 8082;
    location /ws { proxy_pass http://gateways; proxy_http_version 1.1;
      proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";
      proxy_read_timeout 3600s; proxy_send_timeout 3600s; proxy_buffering off; }
  }
}
""".replace(
        "SERVERS", " ".join(servers)
    )
    host = inventory["hosts"]["nginx"]
    ssh(host, key, "cat > /opt/fleet/nginx.conf", input=config.encode())
    ssh(
        host,
        key,
        "sudo docker rm -f fleet >/dev/null 2>&1 || true; sudo docker run -d --name fleet --restart unless-stopped --network host --ulimit nofile=65536:65536 -v /opt/fleet/nginx.conf:/etc/nginx/nginx.conf:ro nginx:1.27.5",
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--model", default="")
    parser.add_argument(
        "--bedrock-region",
        choices=["us-east-1", "us-east-2", "us-west-2"],
        default="us-east-1",
    )
    parser.add_argument(
        "--fixed-now", default="", help="Optional ISO-8601 UTC time for evaluation"
    )
    parser.add_argument(
        "--evaluation",
        action="store_true",
        help="Expose bounded tool evidence only for disposable evaluation",
    )
    parser.add_argument(
        "--password-file",
        type=Path,
        required=True,
        help="Local one-line demo DB password; never committed",
    )
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    password = (
        "dry-run-only" if args.dry_run else args.password_file.read_text().strip()
    )
    if not password or any(c in password for c in "\n\r"):
        parser.error("password must be one nonempty line")
    deploy(
        json.loads(args.inventory.read_text()),
        args.key,
        args.tag,
        password,
        args.model,
        args.evaluation,
        args.fixed_now,
        args.bedrock_region,
        args.dry_run,
    )
