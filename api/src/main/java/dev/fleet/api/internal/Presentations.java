package dev.fleet.api.internal;

import dev.fleet.common.Json;
import dev.fleet.common.rpc.*;

import java.time.Instant;
import java.util.*;

/**
 * Renders each copilot query result in code: the JSON the model reads (with ISO-8601 UTC strings
 * next to epoch values), the conditions shown to the user and the result tables.
 */
final class Presentations {
    private Presentations() {}

    static Presentation assetStatus(AssetStatus status, Instant asOf) {
        var result = new LinkedHashMap<String, Object>();
        result.put("assetId", status.getAssetId());
        result.put("position", status.hasPosition() ? position(status.getPosition()) : null);
        result.put(
                "confirmedStates",
                status.getConfirmedStatesList().stream()
                        .map(
                                state -> {
                                    var row = new LinkedHashMap<String, Object>();
                                    row.put("fence_id", state.getFenceId());
                                    row.put("name", state.getName());
                                    row.put("inside", state.getInside());
                                    row.put(
                                            "last_transition_position_ts",
                                            state.getLastTransitionPositionTs());
                                    row.put(
                                            "last_transition_position_utc",
                                            utc(state.getLastTransitionPositionTs()));
                                    row.put("fence_version", state.getFenceVersion());
                                    return row;
                                })
                        .toList());
        var args = status.getReceipt().getArgumentsMap();
        return build(
                result,
                scope(status.getReceipt())
                        + " · Latest reported position and confirmed port states · State limit: "
                        + args.getOrDefault("stateLimit", "50"),
                asOf);
    }

    private static Map<String, Object> position(LatestPosition position) {
        var result = new LinkedHashMap<String, Object>();
        result.put("tenantId", position.getTenantId());
        result.put("assetId", position.getAssetId());
        result.put("mmsi", position.getMmsi());
        result.put("positionTs", position.getPositionTs());
        result.put("reportTimeUtc", utc(position.getPositionTs()));
        result.put("lat", position.getLat());
        result.put("lon", position.getLon());
        result.put("sog", position.hasSog() ? position.getSog() : null);
        result.put("cog", position.hasCog() ? position.getCog() : null);
        result.put("heading", position.hasHeading() ? position.getHeading() : null);
        result.put("emittedAt", position.getEmittedAt());
        result.put("emittedAtUtc", utc(position.getEmittedAt()));
        result.put("ingestedAt", position.hasIngestedAt() ? position.getIngestedAt() : null);
        result.put(
                "ingestedAtUtc", position.hasIngestedAt() ? utc(position.getIngestedAt()) : null);
        return result;
    }

    static Presentation events(EventsResult events, Instant asOf) {
        var rows =
                events.getEventsList().stream()
                        .map(
                                event -> {
                                    var row = new LinkedHashMap<String, Object>();
                                    row.put("event_id", event.getEventId());
                                    row.put("asset_id", event.getAssetId());
                                    row.put("fence_id", event.getFenceId());
                                    row.put("type", event.getType().name());
                                    row.put("position_ts", event.getPositionTs());
                                    row.put("position_utc", utc(event.getPositionTs()));
                                    row.put("fence_version", event.getFenceVersion());
                                    return row;
                                })
                        .toList();
        var args = events.getReceipt().getArgumentsMap();
        return build(
                rows,
                scope(events.getReceipt())
                        + " · Events: arrivals and departures (ENTER/EXIT) · "
                        + time(args)
                        + " · Newest first · Limit: "
                        + args.getOrDefault("limit", "50"),
                asOf);
    }

    static Presentation fences(FencesResult fences, Instant asOf) {
        var rows =
                fences.getFencesList().stream()
                        .map(
                                fence -> {
                                    var row = new LinkedHashMap<String, Object>();
                                    row.put("id", fence.getId());
                                    row.put("name", fence.getName());
                                    row.put("center_lat", fence.getCenterLat());
                                    row.put("center_lon", fence.getCenterLon());
                                    row.put("enter_radius_m", fence.getEnterRadiusM());
                                    row.put("exit_radius_m", fence.getExitRadiusM());
                                    row.put("version", fence.getVersion());
                                    return row;
                                })
                        .toList();
        return build(
                rows,
                "Scope: our company · Active port areas · Limit: "
                        + fences.getReceipt().getArgumentsMap().getOrDefault("limit", "50"),
                asOf);
    }

    static Presentation nearby(NearbyResult nearby, Instant asOf) {
        var rows =
                nearby.getVesselsList().stream()
                        .map(
                                vessel -> {
                                    var row = new LinkedHashMap<String, Object>();
                                    row.put("assetId", vessel.getAssetId());
                                    row.put("distanceM", vessel.getDistanceM());
                                    return row;
                                })
                        .toList();
        var args = nearby.getReceipt().getArgumentsMap();
        String center =
                args.containsKey("fenceId")
                        ? "validated port center (fence " + args.get("fenceId") + ")"
                        : args.get("lat") + ", " + args.get("lon");
        return build(
                rows,
                scope(nearby.getReceipt())
                        + " · Center: "
                        + center
                        + " · Radius: "
                        + args.get("radiusM")
                        + " m · Closest first · Limit: "
                        + args.getOrDefault("limit", "50"),
                asOf);
    }

    static Presentation assets(AssetSearchResult search, Instant asOf) {
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "assets",
                search.getAssetsList().stream()
                        .map(
                                asset -> {
                                    var row = new LinkedHashMap<String, Object>();
                                    row.put("assetId", asset.getAssetId());
                                    row.put("name", asset.getName());
                                    return row;
                                })
                        .toList());
        result.put("truncated", search.getTruncated());
        return build(
                result,
                "Scope: our company · Exact vessel name: "
                        + search.getReceipt().getArgumentsMap().get("name")
                        + " · At most 50 matches",
                asOf);
    }

    static Presentation summary(EventSummary summary, Instant asOf) {
        var args = summary.getReceipt().getArgumentsMap();
        var result = new LinkedHashMap<String, Object>();
        result.put("totalEvents", summary.getTotalEvents());
        result.put("distinctAssets", summary.getDistinctAssets());
        result.put("fromUtc", utc(args.get("fromTs")));
        result.put("toUtc", utc(args.get("toTs")));
        result.put("truncated", summary.getTruncated());
        result.put(
                "ports",
                summary.getPortsList().stream()
                        .map(
                                port -> {
                                    var row = new LinkedHashMap<String, Object>();
                                    row.put("fenceId", port.getFenceId());
                                    row.put("name", port.getName());
                                    row.put("type", port.getType());
                                    row.put("events", port.getEvents());
                                    row.put("distinctAssets", port.getDistinctAssets());
                                    return row;
                                })
                        .toList());
        return build(
                result,
                scope(summary.getReceipt())
                        + " · Events: "
                        + events(args)
                        + " · "
                        + time(args)
                        + " · Event and distinct-vessel counts by port/type",
                asOf);
    }

    static Presentation fleetState(FleetStateSummary state, Instant asOf) {
        var result = new LinkedHashMap<String, Object>();
        result.put("insideAssets", state.getInsideAssets());
        result.put("truncated", state.getTruncated());
        result.put(
                "ports",
                state.getPortsList().stream()
                        .map(
                                port -> {
                                    var row = new LinkedHashMap<String, Object>();
                                    row.put("fenceId", port.getFenceId());
                                    row.put("name", port.getName());
                                    row.put("insideAssets", port.getInsideAssets());
                                    return row;
                                })
                        .toList());
        result.put(
                "assets",
                state.getAssetsList().stream()
                        .map(
                                asset -> {
                                    var row = new LinkedHashMap<String, Object>();
                                    row.put("assetId", asset.getAssetId());
                                    row.put("fenceId", asset.getFenceId());
                                    return row;
                                })
                        .toList());
        return build(
                result,
                "Scope: our company · Confirmed INSIDE state (not proximity) · Complete counts;"
                        + " at most 50 details",
                asOf);
    }

    static Presentation query(EventQueryResult query, Instant asOf) {
        var args = query.getReceipt().getArgumentsMap();
        var rows =
                query.getRowsList().stream()
                        .map(
                                row -> {
                                    var values = new LinkedHashMap<String, Object>();
                                    if (row.hasFenceId()) values.put("fenceId", row.getFenceId());
                                    if (row.hasAssetId()) values.put("assetId", row.getAssetId());
                                    if (!row.getName().isEmpty()) values.put("name", row.getName());
                                    if (!row.getDayUtc().isEmpty())
                                        values.put("dayUtc", row.getDayUtc());
                                    if (!row.getEventType().isEmpty())
                                        values.put("eventType", row.getEventType());
                                    values.put("value", row.getValue());
                                    return values;
                                })
                        .toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("metric", args.get("metric"));
        result.put("total", query.getTotal());
        result.put("rows", rows);
        result.put("truncated", query.getTruncated());
        result.put("fromTs", Long.parseLong(args.get("fromTs")));
        result.put("toTs", Long.parseLong(args.get("toTs")));
        result.put("fromUtc", utc(args.get("fromTs")));
        result.put("toUtc", utc(args.get("toTs")));
        return build(
                result,
                scope(query.getReceipt())
                        + " · Events: "
                        + events(args)
                        + " · "
                        + time(args)
                        + " · Metric: "
                        + ("distinct_vessels".equals(args.get("metric"))
                                ? "distinct vessels"
                                : "event count")
                        + " · Group by: "
                        + args.get("groupBy")
                        + " · Group limit: 50",
                asOf);
    }

    private static Presentation build(Object result, String conditions, Instant asOf) {
        var presentation =
                Presentation.newBuilder()
                        .setResultJson(Json.write(result))
                        .setConditions(conditions)
                        .setDataAsOfUtc(asOf.toString());
        tables(result).forEach(presentation::addTables);
        return presentation.build();
    }

    private static String scope(QueryReceipt receipt) {
        var ports =
                receipt.getPortsList().stream()
                        .map(port -> port.getName() + " (fence " + port.getId() + ")")
                        .toList();
        var vessels =
                receipt.getVesselsList().stream()
                        .map(vessel -> vessel.getName() + " (MMSI " + vessel.getId() + ")")
                        .toList();
        return "Scope: our company · Ports: "
                + (ports.isEmpty() ? "all matching owned ports" : String.join(", ", ports))
                + " · Vessels: "
                + (vessels.isEmpty() ? "all matching owned vessels" : String.join(", ", vessels));
    }

    private static String events(Map<String, String> args) {
        return switch (args.getOrDefault("eventType", "both")) {
            case "ENTER" -> "arrivals (ENTER)";
            case "EXIT" -> "departures (EXIT)";
            default -> "arrivals and departures (ENTER/EXIT)";
        };
    }

    private static String time(Map<String, String> args) {
        String from = args.containsKey("fromTs") ? utc(args.get("fromTs")) : "unbounded start";
        String to = args.containsKey("toTs") ? utc(args.get("toTs")) : "unbounded end";
        return "Time: " + from + " .. " + to + " (inclusive UTC)";
    }

    private static String utc(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private static String utc(String epochMillis) {
        return epochMillis == null ? null : utc(Long.parseLong(epochMillis));
    }

    private static List<Table> tables(Object result) {
        var tables = new ArrayList<Table>();
        if (result instanceof List<?> rows) {
            tables.add(table("Results", rows));
        } else if (result instanceof Map<?, ?> map) {
            var facts = new ArrayList<List<String>>();
            for (var entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof List<?> rows)
                    tables.add(table(entry.getKey().toString(), rows));
                else if (value instanceof Map<?, ?> nested)
                    tables.add(table(entry.getKey().toString(), List.of(nested)));
                else facts.add(List.of(entry.getKey().toString(), cell(value)));
            }
            if (!facts.isEmpty())
                tables.addFirst(table("Key results", List.of("Fact", "Value"), facts));
        }
        return tables;
    }

    private static Table table(String title, List<?> rows) {
        var columns = new TreeSet<String>();
        rows.stream()
                .limit(50)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .forEach(row -> row.keySet().forEach(key -> columns.add(key.toString())));
        var headers = columns.stream().limit(32).toList();
        var values =
                rows.stream()
                        .limit(50)
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(row -> headers.stream().map(key -> cell(row.get(key))).toList())
                        .toList();
        return table(title, headers, values);
    }

    private static Table table(String title, List<String> columns, List<List<String>> rows) {
        var table = Table.newBuilder().setTitle(title).addAllColumns(columns);
        rows.forEach(row -> table.addRows(TableRow.newBuilder().addAllCells(row)));
        return table.build();
    }

    private static String cell(Object value) {
        if (value == null) return "not available";
        String text =
                value instanceof Map<?, ?> || value instanceof List<?>
                        ? Json.write(value)
                        : value.toString();
        return text.length() <= 512 ? text : text.substring(0, 512) + " [truncated]";
    }
}
