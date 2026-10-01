"""Regression for temporary Docker configuration; no Docker daemon or AWS calls."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ImagePushTest(unittest.TestCase):
    def test_repository_export_does_not_need_uncreated_host_outputs(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "repository_inventory", ROOT / "infra/aws/scripts/repository-inventory.py"
        )
        exporter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(exporter)
        state = {
            "values": {
                "root_module": {
                    "resources": [
                        {
                            "type": "aws_ecr_repository",
                            "name": "service",
                            "index": module,
                            "values": {"repository_url": "registry/" + module},
                        }
                        for module in ("api", "copilot")
                    ]
                }
            }
        }
        result = exporter.inventory(state)
        self.assertEqual(
            result["images"], {"api": "registry/api", "copilot": "registry/copilot"}
        )
        self.assertEqual(result["hosts"], {})
        self.assertNotIn("outputs", state)
        with self.assertRaises(ValueError):
            exporter.inventory({})

    def test_plan_export_marks_placeholders_and_live_deploy_rejects_them(self):
        import importlib.util

        def load(name):
            path = ROOT / "infra/aws/scripts" / (name + ".py")
            spec = importlib.util.spec_from_file_location(name, path)
            result = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(result)
            return result

        exporter = load("repository-inventory")
        deployment = load("deploy")
        resources = [
            {
                "type": "aws_ecr_repository",
                "name": "service",
                "index": module,
                "values": {"name": "fleet-bench/" + module},
            }
            for module in ("api", "copilot")
        ]
        for name, role in (
            ("api", "api"),
            ("copilot", "copilot"),
            ("mysql", "mysql"),
            ("redis", "redis"),
            ("kafka", "kafka"),
            ("loadgen-1", "loadgen"),
        ):
            resources.append(
                {
                    "type": "aws_instance",
                    "name": "host",
                    "index": name,
                    "values": {"instance_type": "m7g.large", "tags": {"role": role}},
                }
            )
        result = exporter.inventory(
            {"planned_values": {"root_module": {"resources": resources}}},
            planned=True,
            hosts=True,
        )
        self.assertTrue(result["planned"])
        self.assertEqual(len(result["hosts"]), 6)
        from unittest.mock import patch
        import contextlib, io

        with patch.object(
            deployment, "ssh", side_effect=AssertionError("Dry run must never SSH")
        ):
            with contextlib.redirect_stdout(io.StringIO()):
                deployment.deploy(
                    result,
                    Path("unused-key"),
                    "test",
                    "not-a-secret",
                    "deepseek.v3-v1:0",
                    evaluation=True,
                    bedrock_region="us-west-2",
                    dry_run=True,
                )
            with self.assertRaises(ValueError):
                deployment.deploy(
                    result,
                    Path("unused-key"),
                    "test",
                    "unused",
                    "deepseek.v3-v1:0",
                    evaluation=True,
                )

    def test_model_switch_dry_run_never_connects_and_rejects_other_models(self):
        import importlib.util, sys, contextlib, io

        sys.path.insert(0, str(ROOT / "infra/aws/scripts"))
        spec = importlib.util.spec_from_file_location(
            "switch_copilot", ROOT / "infra/aws/scripts/switch-copilot.py"
        )
        switcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(switcher)
        values = {
            "project": "fleet-bench",
            "region": "us-east-1",
            "planned": True,
            "hosts": {"copilot": {"role": "copilot"}},
            "images": {"copilot": "registry/copilot"},
        }
        from unittest.mock import patch

        with patch.object(
            switcher, "ssh", side_effect=AssertionError("No dry-run SSH")
        ):
            capture = io.StringIO()
            with contextlib.redirect_stdout(capture):
                switcher.switch(
                    values,
                    Path("key"),
                    "verified",
                    "openai.gpt-oss-120b-1:0",
                    "us-east-1",
                    True,
                )
            command = json.loads(capture.getvalue())["command"]
            subprocess.run(["bash", "-n"], input=command, text=True, check=True)
            self.assertIn("set -e;", command)
            with self.assertRaises(ValueError):
                switcher.switch(
                    values, Path("key"), "verified", "unapproved", "us-east-1", True
                )
            with self.assertRaises(ValueError):
                switcher.switch(
                    values,
                    Path("key"),
                    "verified",
                    "openai.gpt-oss-120b-1:0",
                    "us-east-1",
                )

    def test_verified_images_push_without_build_or_changing_user_config(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            original = temp / "normal-docker"
            original.mkdir()
            config = {
                "cliPluginsExtraDirs": ["/existing/plugins"],
                "auths": {"private": {"auth": "do-not-copy"}},
            }
            source = original / "config.json"
            source.write_text(json.dumps(config))
            before = source.read_bytes()
            commands = temp / "commands"
            fake = temp / "docker"
            fake.write_text(
                """#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
with Path(os.environ['TEST_COMMANDS']).open('a') as log:
    log.write(json.dumps(args)+'\\n')
config=Path(os.environ['DOCKER_CONFIG'])
if args[:2]==['context','inspect']:
    print('unix:///test-docker.sock')
elif args[:2]==['buildx','version']:
    value=json.loads((config/'config.json').read_text())
    assert 'auths' not in value
    assert value['cliPluginsExtraDirs'][0]=='/existing/plugins'
    assert value['cliPluginsExtraDirs'][1].endswith('/normal-docker/cli-plugins')
    Path(os.environ['TEST_TEMP_PATH']).write_text(str(config))
elif args[:2]==['image','inspect']:
    print('arm64')
elif args[0] not in ('tag','push'):
    raise SystemExit('Unexpected docker operation')
"""
            )
            fake.chmod(0o700)
            inventory = temp / "inventory.json"
            inventory.write_text(
                json.dumps(
                    {
                        "region": "us-east-1",
                        "images": {
                            "api": "registry/api",
                            "copilot": "registry/copilot",
                        },
                    }
                )
            )
            temporary_path = temp / "temporary-path"
            environment = dict(
                os.environ,
                PATH=str(temp) + ":" + os.environ["PATH"],
                DOCKER_CONFIG=str(original),
                TEST_COMMANDS=str(commands),
                TEST_TEMP_PATH=str(temporary_path),
            )
            environment.pop("DOCKER_HOST", None)
            subprocess.run(
                [
                    str(ROOT / "infra/aws/scripts/push-images.sh"),
                    str(inventory),
                    "verified",
                ],
                env=environment,
                check=True,
            )
            self.assertEqual(source.read_bytes(), before)
            self.assertFalse(Path(temporary_path.read_text()).exists())
            calls = [json.loads(line) for line in commands.read_text().splitlines()]
            self.assertEqual(sum(c[0] == "push" for c in calls), 2)
            self.assertFalse(any("build" in c for c in calls))


if __name__ == "__main__":
    unittest.main()
