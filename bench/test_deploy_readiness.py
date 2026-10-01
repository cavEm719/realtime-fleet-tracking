import importlib.util
from pathlib import Path
import shlex
import subprocess
import unittest

path = Path(__file__).resolve().parents[1] / "infra/aws/scripts/deploy.py"
spec = importlib.util.spec_from_file_location("fleet_deploy", path)
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class ReadinessTest(unittest.TestCase):
    def test_mysql_password_expands_only_inside_container(self):
        outer = shlex.split(deploy.mysql_readiness_command())
        self.assertEqual(outer[:4], ["timeout", "180", "bash", "-c"])
        subprocess.run(["bash", "-n", "-c", outer[4]], check=True)
        probe = outer[4].removeprefix("until ").split("; do ")[0]
        inner = shlex.split(probe)
        self.assertEqual(inner[:6], ["sudo", "docker", "exec", "fleet", "sh", "-c"])
        self.assertIn("MYSQL_PWD=$MYSQL_ROOT_PASSWORD", inner[6])
        self.assertIn("mysqladmin ping", inner[6])
