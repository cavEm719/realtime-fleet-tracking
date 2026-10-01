import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

path = (
    Path(__file__).resolve().parents[1] / "infra/aws/scripts/ecr-credential-helper.py"
)
spec = importlib.util.spec_from_file_location("ecr_helper", path)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class EcrHelperTest(unittest.TestCase):
    def test_token_is_retrieved_from_instance_role_without_writing_files(self):
        server = "0" * 12 + ".dkr.ecr.us-east-1.amazonaws.com"
        with patch.object(
            helper.subprocess, "check_output", return_value="temporary-token\n"
        ) as get:
            result = helper.credentials(server)
        self.assertEqual(
            result, dict(ServerURL=server, Username="AWS", Secret="temporary-token")
        )
        get.assert_called_once_with(
            ["aws", "ecr", "get-login-password", "--region", "us-east-1"], text=True
        )

    def test_other_registry_or_region_cannot_request_credentials(self):
        with patch.object(helper.subprocess, "check_output") as get:
            for server in [
                "example.com",
                "0" * 12 + ".dkr.ecr.us-west-2.amazonaws.com",
            ]:
                with self.assertRaises(ValueError):
                    helper.credentials(server)
        get.assert_not_called()
