package dev.fleet.api.copilot;

import dev.fleet.common.EventQuerySpec;

import java.util.ArrayList;
import java.util.List;

/** Only whitelisted expressions enter SQL text. All user values remain JDBC parameters. */
public final class EventQuerySql {
    public record Compiled(String totals, String groups, List<Object> parameters) {}

    private EventQuerySql() {}

    public static Compiled compile(EventQuerySpec spec) {
        var parameters = new ArrayList<Object>();
        parameters.add(spec.tenantId());
        parameters.add(spec.fromTs());
        parameters.add(spec.toTs());
        String metric =
                spec.metric().equals("event_count") ? "COUNT(*)" : "COUNT(DISTINCT e.asset_id)";
        var where =
                new StringBuilder(
                        " FROM geofence_event e JOIN fence f ON f.id = e.fence_id AND f.tenant_id ="
                            + " e.tenant_id JOIN asset a ON a.id = e.asset_id AND a.tenant_id ="
                            + " e.tenant_id WHERE e.tenant_id = ? AND e.position_ts >= ? AND"
                            + " e.position_ts <= ?");
        if (spec.eventType() != null) {
            where.append(" AND e.type = ?");
            parameters.add(spec.eventType());
        }
        in(where, parameters, "e.fence_id", spec.ports());
        in(where, parameters, "e.asset_id", spec.vessels());
        String selection;
        String grouping;
        switch (spec.groupBy()) {
            case "port" -> {
                selection =
                        "e.fence_id AS fence_id, NULL AS asset_id, f.name AS name, '' AS day_utc,"
                            + " '' AS event_type";
                grouping = " GROUP BY e.fence_id, f.name ORDER BY e.fence_id";
            }
            case "vessel" -> {
                selection =
                        "NULL AS fence_id, e.asset_id AS asset_id, a.name AS name, '' AS day_utc,"
                            + " '' AS event_type";
                grouping = " GROUP BY e.asset_id, a.name ORDER BY e.asset_id";
            }
            case "day" -> {
                // Arithmetic from the UTC epoch avoids MySQL session timezone conversion.
                String day =
                        "DATE_FORMAT(TIMESTAMPADD(SECOND, FLOOR(e.position_ts / 1000), '1970-01-01"
                            + " 00:00:00'), '%Y-%m-%d')";
                selection =
                        "NULL AS fence_id, NULL AS asset_id, '' AS name, "
                                + day
                                + " AS day_utc, '' AS event_type";
                grouping = " GROUP BY day_utc ORDER BY day_utc";
            }
            case "type" -> {
                selection =
                        "NULL AS fence_id, NULL AS asset_id, '' AS name, '' AS day_utc, e.type AS"
                            + " event_type";
                grouping = " GROUP BY e.type ORDER BY e.type";
            }
            case "none" -> {
                selection =
                        "NULL AS fence_id, NULL AS asset_id, '' AS name, '' AS day_utc, '' AS"
                            + " event_type";
                grouping = "";
            }
            default -> throw new IllegalArgumentException("Unsupported grouping");
        }
        return new Compiled(
                "SELECT " + metric + where,
                "SELECT "
                        + selection
                        + ", "
                        + metric
                        + " AS value"
                        + where
                        + grouping
                        + " LIMIT 51",
                List.copyOf(parameters));
    }

    private static void in(
            StringBuilder sql, List<Object> arguments, String column, List<Long> ids) {
        if (ids.isEmpty()) return;
        sql.append(" AND ")
                .append(column)
                .append(" IN (")
                .append(String.join(",", java.util.Collections.nCopies(ids.size(), "?")))
                .append(")");
        arguments.addAll(ids);
    }
}
