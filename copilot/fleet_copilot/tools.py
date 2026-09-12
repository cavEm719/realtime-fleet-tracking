"""Model tools. Each one calls a read-only API query over gRPC with the server-chosen company.

No tool has a company parameter (F5.2). Tools accept only IDs and fixed enumerations (F5.3); the
API validates them again, executes the query and renders what the model and the user see.
"""
import json

import grpc

from .rpc import fleet_data_pb2 as pb
from .rpc import fleet_data_pb2_grpc as rpc
from .timerange import resolve
from .transparency import Basis, Table, assumptions, fact_table, time_text
from .turn import Turn

REPLICA_DELAYED = "Replicated data is delayed"  # FleetDataService.REPLICA_DELAYED in the API
INVALID = {"error": "Invalid arguments or unavailable tenant-owned resource"}
UNAVAILABLE = {"error": "data temporarily unavailable", "retryable": True}


class MissingReceipt(Exception):
    """The API answered without an execution receipt; its conditions cannot be shown."""


def _fn(name: str, description: str, properties: dict, required: tuple = ()) -> dict:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": {
                "type": "object",
                "properties": properties,
                "required": list(required),
                "additionalProperties": False,
            },
        },
    }


_INT = {"type": "integer"}
_NUM = {"type": "number"}
_STR = {"type": "string"}
_WINDOW = {"type": "string", "enum": ["yesterday", "today", "last24h", "last7days", "thisWeek"]}
_TYPE = {"type": "string", "enum": ["ENTER", "EXIT"]}

DEFINITIONS = [
    _fn("getAssetStatus",
        "Read the latest vessel position and confirmed port states. Asset ID is MMSI.",
        {"assetId": _INT}, ("assetId",)),
    _fn("listEvents",
        "List at most 50 port events, newest first, with optional UTC epoch-millisecond filters.",
        {"assetId": _INT, "fenceId": _INT, "fromTs": _INT, "toTs": _INT, "limit": _INT}),
    _fn("findNearby",
        "Find up to 50 vessels near a fence OR coordinates; radius is meters and capped.",
        {"fenceId": _INT, "lat": _NUM, "lon": _NUM, "radiusM": _NUM}, ("radiusM",)),
    _fn("listFences",
        "List up to 50 active company port fences, with coordinates, radii and versions. This tool"
        " has no user parameters; call with {}.", {}),
    _fn("findAssets",
        "Resolve a vessel display name to owned MMSIs (exact name match, at most 50). Use before"
        " getAssetStatus for a named vessel. Zero or multiple matches require asking the user;"
        " never choose a match.", {"name": _STR}, ("name",)),
    _fn("resolveTimeRange",
        "Convert UTC relative time to exact inclusive epoch milliseconds. window: yesterday (UTC"
        " calendar day), today (midnight to now), last24h, last7days, thisWeek (Monday UTC to"
        " now). Use the returned fromTs/toTs for listEvents; do not calculate epoch values"
        " yourself.", {"window": _WINDOW}, ("window",)),
    _fn("aggregateEvents",
        "Compute full event counts and distinct-vessel counts in SQL, grouped by port and"
        " ENTER/EXIT. Use for counting and busiest-port questions instead of counting listEvents."
        " Supply window OR fromUtc/toUtc ISO-8601 instants. Optional assetId, fenceId, eventType"
        " ENTER/EXIT. Totals are complete; port groups capped at 50 and explicitly marked"
        " truncated.",
        {"assetId": _INT, "fenceId": _INT, "eventType": _TYPE, "window": _WINDOW,
         "fromUtc": _STR, "toUtc": _STR}),
    _fn("getFleetState",
        "Read full fleet-wide confirmed INSIDE counts, grouped by owned port, plus at most 50"
        " asset/fence details. Counts are complete; details may be truncated. Proximity does not"
        " establish confirmed state. This tool has no user parameters; call with {}.", {}),
    _fn("queryEvents",
        "Count port events or distinct vessels using a validated structured spec, never SQL."
        " metric: event_count or distinct_vessels; eventType: ENTER/EXIT or absent for both."
        " ports: fence IDs; vessels: MMSIs; omitted/empty lists include all owned resources."
        " Resolve names first with listFences/findAssets; if zero or multiple matches, ask the"
        " user, never choose. timeWindow: today, yesterday, last24h, last7days or thisWeek (Monday"
        " UTC to now), OR from/to ISO-8601 instants (inclusive, at most 366 days). groupBy: port,"
        " vessel, day (UTC), type, none. Full total and at most 50 groups; distinct group counts"
        " must not be added to obtain the total.",
        {"spec": {
            "type": "object",
            "properties": {
                "metric": {"type": "string", "enum": ["event_count", "distinct_vessels"]},
                "eventType": _TYPE,
                "ports": {"type": "array", "items": _INT},
                "vessels": {"type": "array", "items": _INT},
                "timeWindow": _WINDOW,
                "from": _STR,
                "to": _STR,
                "groupBy": {"type": "string", "enum": ["port", "vessel", "day", "type", "none"]},
            },
            "required": ["metric", "groupBy"],
            "additionalProperties": False,
        }}, ("spec",)),
]
PARAMETERS = {
    d["function"]["name"]: set(d["function"]["parameters"]["properties"]) for d in DEFINITIONS
}
SPEC_FIELDS = set(DEFINITIONS[-1]["function"]["parameters"]["properties"]["spec"]["properties"])


def _integer(value, name: str, positive: bool = True) -> int:
    # bool is an int subclass; fractional or string IDs are rejected rather than coerced.
    if isinstance(value, bool) or not isinstance(value, int) or (positive and value <= 0):
        raise ValueError(f"{name} must be a positive integer")
    return value


def _number(value, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be a number")
    return float(value)


class Tools:
    def __init__(self, channel: grpc.aio.Channel, api_timeout_s: float, max_radius_m: float):
        self.stub = rpc.FleetDataStub(channel)
        self.api_timeout_s = api_timeout_s
        self.max_radius_m = max_radius_m

    async def policy(self, tenant: int, turn: Turn) -> int:
        reply = await self._rpc("GetTenantPolicy", pb.TenantRequest(tenant_id=tenant), turn)
        return reply.llm_rate_per_min

    async def execute(self, name: str, args: dict, turn: Turn) -> dict:
        """Run one tool call; returns the JSON object the model reads."""
        if name not in PARAMETERS:
            return {"error": "Unknown tool"}
        if turn.clarification is not None:
            return {"error": "name resolution requires clarification", "skipped": True}
        if turn.data_unavailable and name != "resolveTimeRange":
            return {"error": "data temporarily unavailable", "skipped": True}
        # Some models encode a zero-parameter call as {"": {}}; normalize only that exact wrapper.
        if not PARAMETERS[name] and args == {"": {}}:
            args = {}
        if not isinstance(args, dict) or not set(args) <= PARAMETERS[name]:
            return INVALID
        try:
            return await getattr(self, "_" + name)(turn, **args)
        except ValueError:
            return INVALID
        except MissingReceipt:
            turn.api_unavailable = True
            return UNAVAILABLE
        except grpc.aio.AioRpcError as error:
            if error.code() == grpc.StatusCode.INVALID_ARGUMENT:
                return INVALID
            if error.code() == grpc.StatusCode.UNAVAILABLE and error.details() == REPLICA_DELAYED:
                turn.replica_delayed = True
            else:
                turn.api_unavailable = True
            return UNAVAILABLE

    async def _rpc(self, method: str, request, turn: Turn):
        # One retry for a transient UNAVAILABLE, both attempts inside the request deadline.
        for attempt in range(2):
            timeout = min(self.api_timeout_s, turn.remaining())
            try:
                return await getattr(self.stub, method)(request, timeout=timeout)
            except grpc.aio.AioRpcError as error:
                retry = (
                    attempt == 0
                    and error.code() == grpc.StatusCode.UNAVAILABLE
                    and error.details() != REPLICA_DELAYED
                )
                if not retry:
                    raise

    async def _query(self, method: str, request, turn: Turn, window: str | None = None) -> dict:
        result = await self._rpc(method, request, turn)
        view = result.presentation
        if not result.HasField("receipt") or not view.result_json:
            # Fail closed: never show conditions that were not verified by the API.
            raise MissingReceipt()
        receipt = result.receipt
        args = dict(receipt.arguments)
        turn.basis.append(Basis(
            tool=receipt.tool,
            arguments=args,
            text=view.conditions,
            assumptions=assumptions(receipt.tool, args, turn.question, window),
            results=[Table(t.title, list(t.columns), [list(r.cells) for r in t.rows])
                     for t in view.tables],
            data_as_of=view.data_as_of_utc or None,
        ))
        return json.loads(view.result_json)

    async def _getAssetStatus(self, turn, assetId):
        request = pb.AssetRequest(tenant_id=turn.tenant_id, asset_id=_integer(assetId, "assetId"))
        return await self._query("GetAssetStatus", request, turn)

    async def _listEvents(self, turn, assetId=None, fenceId=None, fromTs=None, toTs=None, limit=None):
        request = pb.EventsRequest(tenant_id=turn.tenant_id)
        if assetId is not None:
            request.asset_id = _integer(assetId, "assetId")
        if fenceId is not None:
            request.fence_id = _integer(fenceId, "fenceId")
        if fromTs is not None:
            request.from_ts = _integer(fromTs, "fromTs", positive=False)
        if toTs is not None:
            request.to_ts = _integer(toTs, "toTs", positive=False)
        if limit is not None:
            request.limit = _integer(limit, "limit")
        return await self._query("ListEvents", request, turn)

    async def _findNearby(self, turn, radiusM, fenceId=None, lat=None, lon=None):
        radius = _number(radiusM, "radiusM")
        if not 0 < radius <= self.max_radius_m:
            raise ValueError("Invalid radius")
        request = pb.NearbyRequest(tenant_id=turn.tenant_id, radius_m=radius)
        if fenceId is not None:
            request.fence_id = _integer(fenceId, "fenceId")
        if lat is not None:
            request.lat = _number(lat, "lat")
        if lon is not None:
            request.lon = _number(lon, "lon")
        return await self._query("FindNearby", request, turn)

    async def _listFences(self, turn):
        return await self._query("ListFences", pb.FencesRequest(tenant_id=turn.tenant_id), turn)

    async def _findAssets(self, turn, name):
        if not isinstance(name, str) or not name.strip() or len(name) > 128:
            raise ValueError("Invalid vessel name")
        request = pb.AssetSearchRequest(tenant_id=turn.tenant_id, name=name)
        result = await self._query("FindAssets", request, turn)
        if len(result["assets"]) != 1 or result["truncated"]:
            # The model must not pick among several vessels, or guess one that does not exist.
            turn.clarification = (
                "Please specify the vessel MMSI: that name has "
                + ("no matching vessel" if not result["assets"] else "multiple matching vessels")
                + " in your company."
            )
        return result

    async def _resolveTimeRange(self, turn, window):
        if not isinstance(window, str):
            raise ValueError("Invalid window")
        span = resolve(window, None, None, turn.now)
        result = {"fromTs": span.start, "toTs": span.end,
                  "fromUtc": span.from_utc, "toUtc": span.to_utc}
        args = {"fromTs": str(span.start), "toTs": str(span.end)}
        turn.basis.append(Basis(
            tool="resolveTimeRange",
            arguments=args,
            text="UTC time interpretation · " + time_text(args),
            assumptions=assumptions("resolveTimeRange", args, turn.question, window),
            results=[fact_table(result)],
        ))
        return result

    async def _aggregateEvents(self, turn, assetId=None, fenceId=None, eventType=None,
                               window=None, fromUtc=None, toUtc=None):
        span = resolve(window, fromUtc, toUtc, turn.now)
        request = pb.EventsRequest(tenant_id=turn.tenant_id, from_ts=span.start, to_ts=span.end)
        if assetId is not None:
            request.asset_id = _integer(assetId, "assetId")
        if fenceId is not None:
            request.fence_id = _integer(fenceId, "fenceId")
        if eventType is not None:
            request.event_type = eventType
        return await self._query("AggregateEvents", request, turn)

    async def _getFleetState(self, turn):
        return await self._query("GetFleetState", pb.FencesRequest(tenant_id=turn.tenant_id), turn)

    async def _queryEvents(self, turn, spec):
        if not isinstance(spec, dict) or not set(spec) <= SPEC_FIELDS:
            raise ValueError("Unexpected query spec field")
        span = resolve(spec.get("timeWindow"), spec.get("from"), spec.get("to"), turn.now)
        request = pb.EventQueryRequest(
            tenant_id=turn.tenant_id,
            metric=spec.get("metric", ""),
            ports=[_integer(p, "port") for p in spec.get("ports") or []],
            vessels=[_integer(v, "vessel") for v in spec.get("vessels") or []],
            from_ts=span.start,
            to_ts=span.end,
            group_by=spec.get("groupBy", ""),
        )
        if spec.get("eventType") is not None:
            request.event_type = spec["eventType"]
        return await self._query("QueryEvents", request, turn, spec.get("timeWindow"))
