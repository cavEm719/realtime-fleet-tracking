import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch


path = Path(__file__).resolve().parents[1] / "infra/aws/scripts/verify-local-images.py"
spec = importlib.util.spec_from_file_location("fleet_image_verify", path)
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class ImageArtifactTest(unittest.TestCase):
    def check_image(self, contents, copy_failure=False):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            artifact = root / "copilot/target/copilot-0.1.0-SNAPSHOT-exec.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"verified-current-jar")

            def command(*args, **kwargs):
                if args[:2] == ("docker", "create"):
                    return SimpleNamespace(stdout="test-container\n")
                if args[:2] == ("docker", "cp"):
                    if copy_failure:
                        raise RuntimeError("copy failed")
                    Path(args[3]).write_bytes(contents)
                return SimpleNamespace(stdout="")

            with patch.object(verify, "ROOT", root), patch.object(
                verify, "run", side_effect=command
            ) as run:
                try:
                    return verify.verify_artifact("copilot", "test-image")
                finally:
                    run.assert_any_call(
                        "docker", "rm", "test-container", capture_output=True, text=True
                    )

    def test_current_jar_is_accepted(self):
        self.assertEqual(len(self.check_image(b"verified-current-jar")), 64)

    def test_stale_image_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "current packaged JAR"):
            self.check_image(b"older-jar")

    def test_copy_failure_still_removes_temporary_container(self):
        with self.assertRaisesRegex(RuntimeError, "copy failed"):
            self.check_image(b"", copy_failure=True)
