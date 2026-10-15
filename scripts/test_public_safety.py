"""Offline checks of the public-file gate using isolated temporary Git indexes."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

CHECKER = Path(__file__).with_name("check-public-safety.sh").resolve()


class PublicSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        (self.root / "scripts").mkdir()
        shutil.copy2(CHECKER, self.root / "scripts/check-public-safety.sh")
        self.local = self.root / "scripts/.public-safety-local.json"
        self.local.write_text(json.dumps({"public_ips": ["198.51.100.42"]}))
        self.file = self.root / "evidence.txt"
        self.file.write_text("Safe fixture")
        self.stage()

    def tearDown(self):
        self.temp.cleanup()

    def stage(self):
        subprocess.run(
            ["git", "add", "evidence.txt", "scripts/check-public-safety.sh"],
            cwd=self.root,
            check=True,
        )

    def run_check(self):
        env = dict(os.environ)
        env.pop("FLEET_PUBLIC_IP", None)
        return subprocess.run(
            ["bash", "scripts/check-public-safety.sh"],
            cwd=self.root,
            env=env,
            capture_output=True,
            text=True,
        )

    def test_clean_files_and_documented_placeholders_pass(self):
        self.file.write_text(
            "arn:aws:bedrock:us-east-1:<ACCOUNT_ID>:model\npositionTs=1704715200000\nenvironment:\n  COPILOT_MODEL: ${MODEL}\n"
        )
        self.stage()
        self.assertEqual(self.run_check().returncode, 0)

    def test_each_forbidden_kind_fails_without_echoing_value(self):
        bad = [
            "arn:aws:bedrock:us-east-1:" + "1" * 12 + ":model",
            json.dumps({"accountId": "1" * 12}),
            "198.51.100.42",
            "/" + "Users/example/project",
            ("co" + "dex").upper(),
            json.dumps({"En" + "v": ["PRIVATE_VALUE=secret"]}),
            "print" + "env\n",
            "docker inspect --format "
            + "{{range .Config."
            + "Env}}{{println .}}{{end}}",
            "print(dict(os." + "environ))",
            "\n".join(f"PRIVATE_{n}=secret" for n in range(6)) + "\n",
            "env\n",
        ]
        for value in bad:
            with self.subTest(value=value):
                self.file.write_text(value)
                self.stage()
                result = self.run_check()
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(value, result.stderr)

    def test_staged_leak_cannot_be_hidden_by_clean_working_file(self):
        self.file.write_text("/" + "Users/example/project")
        self.stage()
        self.file.write_text("Clean working copy")
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_working_copy_and_binary_leaks_are_checked(self):
        self.file.write_bytes(b"\x00" + b"co" + b"dex" + b"\x00")
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_missing_operator_ip_fails_closed(self):
        self.local.unlink()
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_selective_environment_read_is_not_a_dump(self):
        self.file.write_text("printenv COPILOT_MODEL\n")
        self.stage()
        self.assertEqual(self.run_check().returncode, 0)


if __name__ == "__main__":
    unittest.main()
