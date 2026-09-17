import asyncio
import json
import time

import grpc
import pytest

from fleet_copilot.model import ModelError, TransientModelError
from fleet_copilot.service import DELAYED, OUTAGE, HttpError
from fleet_copilot.settings import Settings
from fleet_copilot.tools import DEFINITIONS, REPLICA_DELAYED

from .conftest import NOW, ScriptedModel, answer, tool_call


async def test_company_comes_from_the_server_and_tools_reject_a_tenant_argument(data, make):
    model = ScriptedModel(
        tool_call("getAssetStatus", {"assetId": 366000000, "tenantId": 2}),
        tool_call("getAssetStatus", {"assetId": 366000000}, "c2"),
        answer("The vessel is reported near Los Angeles."),
    )
    reply = await make(model).chat(1, None, "Where is 366000000?")
    calls = [r for name, r in data.requests if name == "GetAssetStatus"]
    assert [r.tenant_id for r in calls] == [1]  # the first call never reached the API
    rejected = json.loads(model.seen[1][-1]["content"])
    assert rejected == {"error": "Invalid arguments or unavailable tenant-owned resource"}
    assert all("tenant" not in json.dumps(d["function"]["parameters"]).lower() for d in DEFINITIONS)
    assert reply["toolsUsed"] == ["getAssetStatus", "getAssetStatus"]


async def test_query_basis_and_tables_come_from_the_api_and_prose_is_only_the_summary(data, make):
    model = ScriptedModel(tool_call("getAssetStatus", {"assetId": 366000000}), answer("Near the port."))
    reply = await make(model).chat(1, None, "Where is 366000000?")
    text = reply["answer"]
    assert text.startswith("Query basis\n")
    assert "Scope: our company · status" in text
    assert "Port events and states as of: 2024-01-08T11:59:59Z" in text
    assert "| Fact | Value |" in text
    assert text.endswith("Summary\nNear the port.")
    # The model reads the API's rendered JSON, including ISO-8601 UTC times, unchanged.
    assert json.loads(model.seen[1][-1]["content"])["position"]["reportTimeUtc"] == "2024-01-08T11:55:00Z"
    assert reply["queryBasis"][0]["dataAsOf"] == "2024-01-08T11:59:59Z"


async def test_relative_windows_are_resolved_in_code_with_stated_assumptions(data, make):
    spec = {"metric": "event_count", "eventType": "ENTER", "timeWindow": "yesterday", "groupBy": "none"}
    model = ScriptedModel(tool_call("queryEvents", {"spec": spec}), answer("Two arrivals."))
    reply = await make(model).chat(1, None, "How many arrivals yesterday?")
    request = next(r for name, r in data.requests if name == "QueryEvents")
    assert (request.from_ts, request.to_ts) == (1704585600000, 1704671999999)
    notes = reply["queryBasis"][0]["assumptions"]
    assert any(n.startswith("Time interpretation: yesterday = Time: 2024-01-07T00:00:00Z") for n in notes)
    assert any("ENTER event" in n for n in notes)


async def test_ambiguous_or_unknown_names_stop_for_clarification(data, make):
    data.assets = []
    model = ScriptedModel(tool_call("findAssets", {"name": "Nobody"}), answer("should not be used"))
    reply = await make(model).chat(1, None, "Where is Nobody?")
    assert "Please specify the vessel MMSI: that name has no matching vessel" in reply["answer"]
    assert len(model.seen) == 1


@pytest.mark.parametrize(
    "failure, expected",
    [((grpc.StatusCode.UNAVAILABLE, REPLICA_DELAYED), DELAYED),
     ((grpc.StatusCode.UNAVAILABLE, "Data temporarily unavailable"), OUTAGE)],
)
async def test_unavailable_data_is_reported_without_inventing_results(data, make, failure, expected):
    data.failure = failure
    model = ScriptedModel(tool_call("getAssetStatus", {"assetId": 366000000}), answer("invented"))
    reply = await make(model).chat(1, None, "Where is 366000000?")
    assert reply["answer"].endswith("Summary\n" + expected)
    assert "invented" not in reply["answer"]
    attempts = len([1 for name, _ in data.requests if name == "GetAssetStatus"])
    assert attempts == (1 if expected == DELAYED else 2)  # a lagging replica is not retried


async def test_tool_calls_are_capped_at_eight_per_question(data, make):
    model = ScriptedModel(tool_call("getAssetStatus", {"assetId": 366000000}))
    reply = await make(model).chat(1, None, "loop")
    assert reply["answer"].split("Summary\n")[1].startswith("Tool call limit reached")
    assert len([1 for name, _ in data.requests if name == "GetAssetStatus"]) == 8


async def test_the_whole_question_is_bounded_by_the_deadline(data, make):
    settings = Settings(fixed_now=NOW, timeout_s=0.3, token_bucket_lua=Settings.from_env().token_bucket_lua)
    copilot = make(ScriptedModel(answer("late"), delay=1.0), settings)
    started = time.monotonic()
    with pytest.raises(HttpError) as error:
        await copilot.chat(1, None, "slow")
    assert error.value.status == 504
    assert time.monotonic() - started < 0.8


async def test_a_follow_up_is_answered_by_another_instance(data, make, redis):
    first = ScriptedModel(answer("Los Angeles had 2 arrivals."))
    second = ScriptedModel(answer("Seattle had none."))
    reply = await make(first, shared_redis=redis).chat(1, None, "Arrivals at Los Angeles?")
    await make(second, shared_redis=redis).chat(1, reply["conversationId"], "And Seattle?")
    contents = [m["content"] for m in second.seen[0]]
    assert "Arrivals at Los Angeles?" in contents and "Los Angeles had 2 arrivals." in contents
    # The same conversation ID under another company sees none of that history.
    third = ScriptedModel(answer("ok"))
    await make(third, shared_redis=redis).chat(2, reply["conversationId"], "And Seattle?")
    assert "Arrivals at Los Angeles?" not in [m["content"] for m in third.seen[0]]


async def test_questions_are_limited_per_company_per_minute(data, make):
    data.rate = 2
    copilot = make(ScriptedModel(answer("ok")))
    await copilot.chat(1, None, "one")
    await copilot.chat(1, None, "two")
    with pytest.raises(HttpError) as error:
        await copilot.chat(1, None, "three")
    assert error.value.status == 429 and int(error.value.headers["Retry-After"]) >= 1
    await copilot.chat(2, None, "another company is unaffected")


async def test_transient_model_errors_retry_once_and_a_failing_provider_opens_the_circuit(data, make):
    flaky = make(ScriptedModel(TransientModelError("RateLimitError"), answer("ok")))
    reply = await flaky.chat(1, None, "hello")
    assert [a["success"] for a in reply["evaluation"]["attempts"]] == [False, True]

    broken = make(ScriptedModel(ModelError("AuthenticationError")))
    for _ in range(5):
        with pytest.raises(HttpError) as error:
            await broken.chat(1, None, "hello")
        assert error.value.status == 502
    assert broken.breaker.state == "OPEN"
    with pytest.raises(HttpError) as error:
        await broken.chat(1, None, "hello")
    assert error.value.message == "Model provider circuit is open"


async def test_hundreds_of_questions_wait_on_the_model_concurrently(data, make):
    # N6.3: about 330 requests waiting on the model at once; they must not queue behind each other.
    data.rate = 10_000
    copilot = make(ScriptedModel(answer("ok"), delay=0.5))
    started = time.monotonic()
    replies = await asyncio.gather(*(copilot.chat(t % 50 + 1, None, "hi") for t in range(330)))
    assert len(replies) == 330
    assert time.monotonic() - started < 5


async def test_an_api_outage_is_answered_without_calling_the_model(data, make):
    data.channel = __import__("grpc").aio.insecure_channel("127.0.0.1:1")  # nothing listens here
    model = ScriptedModel(answer("invented"))
    reply = await make(model).chat(1, None, "Where is 366000000?")
    assert reply["answer"].endswith("Summary\n" + OUTAGE)
    assert model.seen == []
