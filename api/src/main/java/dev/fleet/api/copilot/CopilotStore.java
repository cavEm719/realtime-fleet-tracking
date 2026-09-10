package dev.fleet.api.copilot;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class CopilotStore {
    public record Fence(
            long id,
            String name,
            double lat,
            double lon,
            double enterRadiusM,
            double exitRadiusM,
            long version) {}

    public record ConfirmedState(
            long fenceId,
            String name,
            boolean inside,
            long lastTransitionPositionTs,
            long fenceVersion) {}

    public record Event(
            String eventId,
            long assetId,
            long fenceId,
            dev.fleet.common.GeofenceEvent.Type type,
            long positionTs,
            long fenceVersion,
            long createdAtMicros,
            long id) {}

    private final JdbcTemplate sql;
    private final JdbcTemplate primary;
    private final dev.fleet.api.internal.AiReadDatabase ai;

    /** Copilot reads use the read-only pool; gateway resume reads the primary. */
    public CopilotStore(JdbcTemplate primary, dev.fleet.api.internal.AiReadDatabase ai) {
        this.sql = ai.sql();
        this.primary = primary;
        this.ai = ai;
    }

    public String resourceName(long tenant, long id, boolean port) {
        String table = port ? "fence" : "asset";
        var names =
                sql.query(
                        "SELECT name FROM " + table + " WHERE tenant_id = ? AND id = ?",
                        (row, n) -> row.getString(1),
                        tenant,
                        id);
        if (names.size() != 1) throw new IllegalArgumentException("Owned resource not found");
        return names.getFirst();
    }

    public dev.fleet.common.rpc.EventQueryResult queryEvents(dev.fleet.common.EventQuerySpec spec) {
        return ai.snapshot(() -> queryEventsRead(spec));
    }

    private dev.fleet.common.rpc.EventQueryResult queryEventsRead(
            dev.fleet.common.EventQuerySpec spec) {
        var query = EventQuerySql.compile(spec);
        long total = sql.queryForObject(query.totals(), Long.class, query.parameters().toArray());
        var rows =
                sql.query(
                        query.groups(),
                        (row, n) -> {
                            var result =
                                    dev.fleet.common.rpc.EventQueryRow.newBuilder()
                                            .setName(row.getString("name"))
                                            .setDayUtc(row.getString("day_utc"))
                                            .setEventType(row.getString("event_type"))
                                            .setValue(row.getLong("value"));
                            if (row.getObject("fence_id") != null)
                                result.setFenceId(row.getLong("fence_id"));
                            if (row.getObject("asset_id") != null)
                                result.setAssetId(row.getLong("asset_id"));
                            return result.build();
                        },
                        query.parameters().toArray());
        return dev.fleet.common.rpc.EventQueryResult.newBuilder()
                .setTotal(total)
                .addAllRows(rows.stream().limit(50).toList())
                .setTruncated(rows.size() > 50)
                .build();
    }

    public boolean ownsAsset(long tenant, long asset) {
        return owns(sql, tenant, asset);
    }

    /** Gateway resume must not depend on replica availability. */
    public boolean ownsAssetOnPrimary(long tenant, long asset) {
        return owns(primary, tenant, asset);
    }

    private static boolean owns(JdbcTemplate sql, long tenant, long asset) {
        return sql.queryForObject(
                        "SELECT COUNT(*) FROM asset WHERE tenant_id = ? AND id = ?",
                        Integer.class,
                        tenant,
                        asset)
                == 1;
    }

    public boolean ownsFence(long tenant, long fence) {
        return sql.queryForObject(
                        "SELECT COUNT(*) FROM fence WHERE tenant_id = ? AND id = ?",
                        Integer.class,
                        tenant,
                        fence)
                == 1;
    }

    public Fence fence(long tenant, long id) {
        return sql
                .query(
                        """
                        SELECT id, name, center_lat, center_lon, enter_radius_m, exit_radius_m, version
                        FROM fence WHERE tenant_id = ? AND id = ? AND deleted = false
                        """,
                        (r, n) ->
                                new Fence(
                                        r.getLong(1),
                                        r.getString(2),
                                        r.getDouble(3),
                                        r.getDouble(4),
                                        r.getDouble(5),
                                        r.getDouble(6),
                                        r.getLong(7)),
                        tenant,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Fence not found"));
    }

    public List<Fence> fences(long tenant) {
        return fences(tenant, 50);
    }

    public List<Fence> fences(long tenant, int limit) {
        return sql.query(
                """
                SELECT id, name, center_lat, center_lon, enter_radius_m, exit_radius_m, version
                FROM fence WHERE tenant_id = ? AND deleted = false ORDER BY id LIMIT ?
                """,
                (r, n) ->
                        new Fence(
                                r.getLong("id"),
                                r.getString("name"),
                                r.getDouble("center_lat"),
                                r.getDouble("center_lon"),
                                r.getDouble("enter_radius_m"),
                                r.getDouble("exit_radius_m"),
                                r.getLong("version")),
                tenant,
                limit);
    }

    public List<ConfirmedState> states(long tenant, long asset) {
        return states(tenant, asset, 50);
    }

    public List<ConfirmedState> states(long tenant, long asset, int limit) {
        return sql.query(
                """
                SELECT s.fence_id, f.name, s.inside, s.last_transition_position_ts, s.fence_version
                FROM asset_fence_state s JOIN fence f ON f.id = s.fence_id AND f.tenant_id = s.tenant_id
                WHERE s.tenant_id = ? AND s.asset_id = ? AND f.deleted = false
                ORDER BY s.fence_id LIMIT ?
                """,
                (r, n) ->
                        new ConfirmedState(
                                r.getLong("fence_id"),
                                r.getString("name"),
                                r.getBoolean("inside"),
                                r.getLong("last_transition_position_ts"),
                                r.getLong("fence_version")),
                tenant,
                asset,
                limit);
    }

    public List<Event> events(long tenant, Long asset, Long fence, Long from, Long to, int limit) {
        var args = new ArrayList<Object>();
        args.add(tenant);
        var query =
                new StringBuilder(
                        """
                        SELECT event_id, asset_id, fence_id, type, position_ts, fence_version, CAST(UNIX_TIMESTAMP(created_at) * 1000000 AS SIGNED) AS created_at_micros, id
                        FROM geofence_event WHERE tenant_id = ?
                        """);
        filter(query, args, "asset_id = ?", asset);
        filter(query, args, "fence_id = ?", fence);
        filter(query, args, "position_ts >= ?", from);
        filter(query, args, "position_ts <= ?", to);
        query.append(" ORDER BY position_ts DESC, id DESC LIMIT ?");
        args.add(limit);
        return sql.query(
                query.toString(),
                (r, n) ->
                        new Event(
                                r.getString("event_id"),
                                r.getLong("asset_id"),
                                r.getLong("fence_id"),
                                dev.fleet.common.GeofenceEvent.Type.valueOf(r.getString("type")),
                                r.getLong("position_ts"),
                                r.getLong("fence_version"),
                                r.getLong("created_at_micros"),
                                r.getLong("id")),
                args.toArray());
    }

    public record NamedAsset(long assetId, String name) {}

    public record PortCount(
            long fenceId, String name, String type, long events, long distinctAssets) {}

    public record EventSummary(
            long totalEvents, long distinctAssets, List<PortCount> ports, boolean truncated) {}

    public record StatePort(long fenceId, String name, long insideAssets) {}

    public record InsideAsset(long assetId, long fenceId) {}

    public record FleetState(
            long insideAssets,
            List<StatePort> ports,
            List<InsideAsset> assets,
            boolean truncated) {}

    public List<NamedAsset> findAssets(long tenant, String name) {
        return sql.query(
                "SELECT id, name FROM asset WHERE tenant_id = ? AND name = ? ORDER BY id LIMIT 51",
                (r, n) -> new NamedAsset(r.getLong(1), r.getString(2)),
                tenant,
                name);
    }

    public EventSummary aggregateEvents(
            long tenant, Long asset, Long fence, Long from, Long to, String type) {
        return ai.snapshot(() -> aggregateEventsRead(tenant, asset, fence, from, to, type));
    }

    private EventSummary aggregateEventsRead(
            long tenant, Long asset, Long fence, Long from, Long to, String type) {
        var args = new ArrayList<Object>();
        args.add(tenant);
        var where =
                new StringBuilder(
                        " FROM geofence_event e JOIN fence f ON f.id = e.fence_id AND f.tenant_id ="
                                + " e.tenant_id WHERE e.tenant_id = ?");
        filter(where, args, "e.asset_id = ?", asset);
        filter(where, args, "e.fence_id = ?", fence);
        filter(where, args, "e.position_ts >= ?", from);
        filter(where, args, "e.position_ts <= ?", to);
        filter(where, args, "e.type = ?", type);
        var totals =
                sql.queryForMap(
                        "SELECT COUNT(*) AS events, COUNT(DISTINCT e.asset_id) AS assets" + where,
                        args.toArray());
        var ports =
                sql.query(
                        "SELECT e.fence_id, f.name, e.type, COUNT(*), COUNT(DISTINCT e.asset_id)"
                                + where
                                + " GROUP BY e.fence_id, f.name, e.type ORDER BY e.fence_id, e.type"
                                + " LIMIT 51",
                        (r, n) ->
                                new PortCount(
                                        r.getLong(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getLong(4),
                                        r.getLong(5)),
                        args.toArray());
        return new EventSummary(
                ((Number) totals.get("events")).longValue(),
                ((Number) totals.get("assets")).longValue(),
                ports.stream().limit(50).toList(),
                ports.size() > 50);
    }

    public FleetState fleetState(long tenant) {
        return ai.snapshot(() -> fleetStateRead(tenant));
    }

    private FleetState fleetStateRead(long tenant) {
        String where =
                " FROM asset_fence_state s JOIN fence f ON f.id = s.fence_id AND f.tenant_id ="
                        + " s.tenant_id WHERE s.tenant_id = ? AND s.inside = true AND f.deleted ="
                        + " false";
        long total =
                sql.queryForObject("SELECT COUNT(DISTINCT s.asset_id)" + where, Long.class, tenant);
        var ports =
                sql.query(
                        "SELECT s.fence_id, f.name, COUNT(DISTINCT s.asset_id)"
                                + where
                                + " GROUP BY s.fence_id, f.name ORDER BY s.fence_id LIMIT 51",
                        (r, n) -> new StatePort(r.getLong(1), r.getString(2), r.getLong(3)),
                        tenant);
        var assets =
                sql.query(
                        "SELECT s.asset_id, s.fence_id"
                                + where
                                + " ORDER BY s.asset_id, s.fence_id LIMIT 51",
                        (r, n) -> new InsideAsset(r.getLong(1), r.getLong(2)),
                        tenant);
        return new FleetState(
                total,
                ports.stream().limit(50).toList(),
                assets.stream().limit(50).toList(),
                ports.size() > 50 || assets.size() > 50);
    }

    public Event eventById(long tenant, String eventId) {
        return primary
                .query(
                        "SELECT event_id, asset_id, fence_id, type, position_ts, fence_version,"
                            + " CAST(UNIX_TIMESTAMP(created_at) * 1000000 AS SIGNED) AS"
                            + " created_at_micros, id FROM geofence_event WHERE tenant_id = ? AND"
                            + " event_id = ?",
                        (r, n) ->
                                new Event(
                                        r.getString(1),
                                        r.getLong(2),
                                        r.getLong(3),
                                        dev.fleet.common.GeofenceEvent.Type.valueOf(r.getString(4)),
                                        r.getLong(5),
                                        r.getLong(6),
                                        r.getLong(7),
                                        r.getLong(8)),
                        tenant,
                        eventId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Resume cursor unavailable"));
    }

    public List<Event> eventsSince(
            long tenant,
            List<Long> assets,
            long fromMicros,
            Long pageMicros,
            Long pageId,
            int limit) {
        var args = new ArrayList<Object>();
        Collections.addAll(args, tenant, java.math.BigDecimal.valueOf(fromMicros, 6));
        var query =
                new StringBuilder(
                        """
                        SELECT event_id, asset_id, fence_id, type, position_ts, fence_version,
                            CAST(UNIX_TIMESTAMP(created_at) * 1000000 AS SIGNED) AS created_at_micros, id
                        FROM geofence_event WHERE tenant_id = ? AND created_at >= FROM_UNIXTIME(?)
                        """);
        if (pageMicros != null) {
            query.append(
                    " AND (created_at > FROM_UNIXTIME(?) OR (created_at = FROM_UNIXTIME(?) AND id >"
                            + " ?))");
            var timestamp = java.math.BigDecimal.valueOf(pageMicros, 6);
            Collections.addAll(args, timestamp, timestamp, pageId);
        }
        if (!assets.isEmpty()) {
            query.append(" AND asset_id IN (")
                    .append(String.join(",", Collections.nCopies(assets.size(), "?")))
                    .append(")");
            args.addAll(assets);
        }
        query.append(" ORDER BY created_at, id LIMIT ?");
        args.add(limit);
        return primary.query(
                query.toString(),
                (r, n) ->
                        new Event(
                                r.getString(1),
                                r.getLong(2),
                                r.getLong(3),
                                dev.fleet.common.GeofenceEvent.Type.valueOf(r.getString(4)),
                                r.getLong(5),
                                r.getLong(6),
                                r.getLong(7),
                                r.getLong(8)),
                args.toArray());
    }

    private void filter(StringBuilder query, List<Object> args, String clause, Object value) {
        if (value != null) {
            query.append(" AND ").append(clause);
            args.add(value);
        }
    }
}
