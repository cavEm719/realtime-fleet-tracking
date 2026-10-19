package dev.fleet.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.fleet.api.copilot.CopilotStore;
import dev.fleet.api.internal.FleetQueries;
import dev.fleet.common.rpc.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

class QueryReceiptTest {
    private final CopilotStore store = mock(CopilotStore.class);
    private final FleetQueries queries =
            new FleetQueries(store, mock(StringRedisTemplate.class), 50000);

    @Test
    void foreignPortAndVesselStopBeforeSql() {
        when(store.resourceName(1, 2, true)).thenThrow(new IllegalArgumentException("foreign"));
        var request =
                EventQueryRequest.newBuilder()
                        .setTenantId(1)
                        .setMetric("event_count")
                        .setGroupBy("none")
                        .setFromTs(0)
                        .setToTs(1000)
                        .addPorts(2)
                        .build();
        assertThatThrownBy(() -> queries.queryEvents(request))
                .isInstanceOf(IllegalArgumentException.class);
        when(store.resourceName(1, 366000100, false))
                .thenThrow(new IllegalArgumentException("foreign"));
        assertThatThrownBy(
                        () ->
                                queries.queryEvents(
                                        request.toBuilder()
                                                .clearPorts()
                                                .addVessels(366000100)
                                                .build()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store).resourceName(1, 2, true);
        verify(store).resourceName(1, 366000100, false);
        verify(store, never()).queryEvents(any());
    }

    @Test
    void receiptAndSqlReceiveIdenticalCanonicalArgumentsIncludingEpochZero() {
        when(store.resourceName(1, 1, true)).thenReturn("Los Angeles");
        when(store.resourceName(1, 4, true)).thenReturn("Seattle");
        when(store.resourceName(1, 366000000, false)).thenReturn("Atlas");
        when(store.queryEvents(any()))
                .thenReturn(EventQueryResult.newBuilder().setTotal(8).build());
        var request =
                EventQueryRequest.newBuilder()
                        .setTenantId(1)
                        .setMetric("event_count")
                        .setEventType("ENTER")
                        .setGroupBy("port")
                        .setFromTs(0)
                        .setToTs(1000)
                        .addAllPorts(List.of(4L, 1L, 1L))
                        .addVessels(366000000)
                        .build();
        var result = queries.queryEvents(request);
        var receipt = result.getReceipt();
        assertThat(receipt.getArgumentsMap())
                .containsEntry("ports", "[1,4]")
                .containsEntry("fromTs", "0")
                .containsEntry("toTs", "1000")
                .doesNotContainKey("tenantId");
        assertThat(receipt.getPortsList())
                .extracting(QueryName::getName)
                .containsExactly("Los Angeles", "Seattle");
        assertThat(receipt.getVessels(0).getName()).isEqualTo("Atlas");
        verify(store)
                .queryEvents(
                        argThat(
                                spec ->
                                        spec.ports().equals(List.of(1L, 4L))
                                                && spec.fromTs() == 0
                                                && spec.toTs() == 1000
                                                && spec.tenantId() == 1));
    }
}
