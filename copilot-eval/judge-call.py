#!/usr/bin/env python3
"""One explicit paid judge request on a loadgen host; prints response, never credentials."""
import json
import sys
import argparse


def invoke(request, region="us-east-1"):
    import boto3
    import botocore.session
    from botocore.config import Config
    from botocore.credentials import InstanceMetadataProvider
    from botocore.utils import InstanceMetadataFetcher

    allowed = {"openai.gpt-oss-120b-1:0": "us-east-1", "deepseek.v3-v1:0": "us-west-2", "mistral.mistral-large-2407-v1:0": "us-west-2", "us.meta.llama4-maverick-17b-instruct-v1:0": "us-east-1"}
    if allowed.get(request["modelId"]) != region:
        raise ValueError("Use only the exact approved judge model and region")
    if request["inferenceConfig"].get("maxTokens") != 4096:
        raise ValueError("Judge output cap is 4096 tokens")
    if len(json.dumps(request).encode()) > 256000:
        raise ValueError("Judge request exceeds the evidence size cap")
    session = botocore.session.get_session()
    session.get_component("credential_provider").providers = [
        InstanceMetadataProvider(
            iam_role_fetcher=InstanceMetadataFetcher(timeout=2, num_attempts=1)
        )
    ]
    client = boto3.Session(botocore_session=session).client(
        "bedrock-runtime",
        region_name=region,
        config=Config(
            retries={"total_max_attempts": 1}, read_timeout=45, connect_timeout=5
        ),
    )
    try:
        response = client.converse(**request)
    except client.exceptions.ThrottlingException:
        return dict(error="ThrottlingException", transient=True, usageUnknown=True)
    except Exception as error:
        status = getattr(error, "response", {}).get("ResponseMetadata", {}).get("HTTPStatusCode", 0)
        return dict(error=type(error).__name__, transient=status == 429 or status >= 500, usageUnknown=status == 429 or status >= 500, status=status)
    return dict(
        usage=response["usage"],
        output=response["output"],
        stopReason=response.get("stopReason"),
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--region", choices=["us-east-1", "us-west-2"], required=True)
    args = parser.parse_args()
    print(json.dumps(invoke(json.load(sys.stdin), args.region)))
