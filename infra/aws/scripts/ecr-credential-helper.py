#!/usr/bin/env python3
"""Docker obtains a short-lived ECR token in memory using the host instance role."""
import json
import re
import subprocess
import sys


def credentials(server):
    if not re.fullmatch(r"[0-9]{12}\.dkr\.ecr\.us-east-1\.amazonaws\.com", server):
        raise ValueError("Only the benchmark's us-east-1 ECR registry is permitted")
    token = subprocess.check_output(
        ["aws", "ecr", "get-login-password", "--region", "us-east-1"], text=True
    ).strip()
    return {"ServerURL": server, "Username": "AWS", "Secret": token}


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] != "get":
        raise SystemExit("This helper only retrieves tokens; it never stores them")
    print(json.dumps(credentials(sys.stdin.read().strip())))
