import asyncio
import json
from datetime import datetime, timezone

import fakeredis
import grpc
import pytest

from fleet_copilot.memory import ConversationStore
from fleet_copilot.model import Completion
from fleet_copilot.ratelimit import QuestionLimiter
from fleet_copilot.rpc import fleet_data_pb2 as pb
from fleet_copilot.rpc import fleet_data_pb2_grpc as rpc
from fleet_copilot.service import Copilot
from fleet_copilot.settings import Settings
from fleet_copilot.tools import REPLICA_DELAYED, Tools

NOW = datetime(2024, 1, 8, 12, tzinfo=timezone.utc)


def presentation(result: dict, conditions: str) -> pb.Presentation:
    return pb.Presentation(
        result_json=json.dumps(result),
        conditions=conditions,
        tables=[pb.Table(title="Key results", columns=["Fact", "Value"],
                         rows=[pb.TableRow(cells=[k, str(v)]) for k, v in result.items()])],
        data_as_of_utc="2024-01-08T11:59:59Z",
    )


class FakeData(rpc.FleetDataServicer):
    """In-process stand-in for the API's read-only query service."""

    def __init__(self):
        self.requests: list[tuple[str, object]] = []
        self.failure: tuple[grpc.StatusCode, str] | None = None
        self.assets = [pb.NamedAsset(asset_id=366000000, name="Ever Given")]
        self.rate = 100

    async def _fail(self, context):
        if self.failure:
            await context.abort(*self.failure)

    async def GetTenantPolicy(self, request, context):
        self.requests.append(("GetTenantPolicy", request))
        return pb.TenantPolicy(llm_rate_per_min=self.rate)

    async def GetAssetStatus(self, request, context):
        self.requests.append(("GetAssetStatus", request))
        await self._fail(context)
        if request.tenant_id != 1:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "not owned")
        receipt = pb.QueryReceipt(tool="getAssetStatus", arguments={"assetId": str(request.asset_id)})
        result = {"assetId": request.asset_id, "position": {"reportTimeUtc": "2024-01-08T11:55:00Z"}}
        return pb.AssetStatus(asset_id=request.asset_id, receipt=receipt,
                              presentation=presentation(result, "Scope: our company · status"))

    async def FindAssets(self, request, context):
        self.requests.append(("FindAssets", request))
        await self._fail(context)
        receipt = pb.QueryReceipt(tool="findAssets", arguments={"name": request.name})
        result = {"assets": [{"assetId": a.asset_id, "name": a.name} for a in self.assets],
                  "truncated": False}
        return pb.AssetSearchResult(assets=self.assets, receipt=receipt,
                                    presentation=presentation(result, "Scope: our company · name"))

    async def QueryEvents(self, request, context):
        self.requests.append(("QueryEvents", request))
        await self._fail(context)
        receipt = pb.QueryReceipt(tool="queryEvents", arguments={
            "metric": request.metric, "groupBy": request.group_by, "eventType": request.event_type,
            "fromTs": str(request.from_ts), "toTs": str(request.to_ts)})
        result = {"metric": request.metric, "total": 2}
        return pb.EventQueryResult(total=2, receipt=receipt,
                                   presentation=presentation(result, "Scope: our company · count"))


def tool_call(name: str, args: dict, call_id: str = "c1") -> dict:
    return {"role": "assistant", "content": "", "tool_calls": [
        {"id": call_id, "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}]}


def answer(text: str) -> dict:
    return {"role": "assistant", "content": text}


class ScriptedModel:
    """Returns queued assistant messages; records the messages it was sent."""

    def __init__(self, *replies, delay: float = 0.0):
        self.replies = list(replies)
        self.delay = delay
        self.seen: list[list[dict]] = []

    async def complete(self, messages, tools, timeout_s):
        self.seen.append(json.loads(json.dumps(messages)))
        if self.delay:
            await asyncio.sleep(self.delay)
        reply = self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]
        if isinstance(reply, Exception):
            raise reply
        return Completion(reply, 10, 5, False)


@pytest.fixture
async def data():
    server = grpc.aio.server()
    fake = FakeData()
    rpc.add_FleetDataServicer_to_server(fake, server)
    port = server.add_insecure_port("127.0.0.1:0")
    await server.start()
    channel = grpc.aio.insecure_channel(f"127.0.0.1:{port}")
    fake.channel = channel
    yield fake
    await channel.close()
    await server.stop(None)


@pytest.fixture
def redis():
    return fakeredis.FakeAsyncRedis(server=fakeredis.FakeServer())


@pytest.fixture
def make(data, redis):
    def build(model, settings: Settings | None = None, shared_redis=None):
        config = settings or Settings(fixed_now=NOW, evaluation_enabled=True,
                                      token_bucket_lua=Settings.from_env().token_bucket_lua)
        store = shared_redis or redis
        return Copilot(config, model, Tools(data.channel, config.api_timeout_s, config.max_radius_m),
                       ConversationStore(store, config.memory_ttl_s, config.memory_max_turns),
                       QuestionLimiter(store, config.token_bucket_lua))
    return build


__all__ = ["REPLICA_DELAYED", "ScriptedModel", "answer", "tool_call", "NOW"]
