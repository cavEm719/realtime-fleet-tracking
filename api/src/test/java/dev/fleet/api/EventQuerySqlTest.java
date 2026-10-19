package dev.fleet.api;

import static org.assertj.core.api.Assertions.*;

import dev.fleet.api.copilot.EventQuerySql;
import dev.fleet.common.EventQuerySpec;

import org.junit.jupiter.api.Test;

import java.util.List;

class EventQuerySqlTest {
    private EventQuerySpec spec(String metric, String group) {
        return new EventQuerySpec(
                1, metric, "ENTER", List.of(4L, 1L, 1L), List.of(366000000L), 1000, 2000, group);
    }

    @Test
    void identifiersAndValuesStayInParametersAndReceiptUsesCanonicalSpec() {
        var spec = spec("event_count", "port");
        var query = EventQuerySql.compile(spec);
        assertThat(query.parameters())
                .containsExactly(1L, 1000L, 2000L, "ENTER", 1L, 4L, 366000000L);
        assertThat(query.groups())
                .contains("GROUP BY e.fence_id, f.name", "e.fence_id IN (?,?)", "LIMIT 51")
                .doesNotContain("366000000", "ENTER");
        assertThat(spec.request().getPortsList()).containsExactly(1L, 4L);
        assertThat(EventQuerySpec.from(spec.request())).isEqualTo(spec);
        assertThatThrownBy(
                        () -> EventQuerySpec.from(spec.request().toBuilder().clearFromTs().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allWhitelistedGroupsCompileAndDistinctTotalsAreNotGroupSums() {
        for (String group : List.of("port", "vessel", "day", "type", "none")) {
            var query = EventQuerySql.compile(spec("distinct_vessels", group));
            assertThat(query.totals())
                    .startsWith("SELECT COUNT(DISTINCT e.asset_id)")
                    .doesNotContain("GROUP BY");
            assertThat(query.groups()).contains("COUNT(DISTINCT e.asset_id) AS value");
        }
        assertThat(EventQuerySql.compile(spec("event_count", "day")).groups())
                .contains("TIMESTAMPADD", "'1970-01-01 00:00:00'")
                .doesNotContain("FROM_UNIXTIME");
    }

    @Test
    void invalidRangesMetricsAndGroupingsFailBeforeCompilation() {
        assertThatThrownBy(() -> spec("SUM(password)", "port"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec("event_count", "tenant_id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec(null, "port")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new EventQuerySpec(
                                        1, "event_count", null, List.of(), List.of(), 2, 1, "none"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new EventQuerySpec(
                                        1,
                                        "event_count",
                                        null,
                                        List.of(),
                                        List.of(),
                                        0,
                                        367L * 86400000,
                                        "none"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new EventQuerySpec(
                                        1,
                                        "event_count",
                                        "DROP",
                                        List.of(),
                                        List.of(),
                                        0,
                                        1,
                                        "none"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
