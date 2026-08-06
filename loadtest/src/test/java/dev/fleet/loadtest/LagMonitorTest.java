package dev.fleet.loadtest;

import static org.mockito.Mockito.*;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DeleteConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

class LagMonitorTest {
    @Test
    void retryRefreshesGroupsAfterPartialDeletion() throws Exception {
        Admin admin = mock(Admin.class);
        var first = mock(ListConsumerGroupsResult.class);
        var remaining = mock(ListConsumerGroupsResult.class);
        when(first.all()).thenReturn(KafkaFuture.completedFuture(List.of(
                new ConsumerGroupListing("already-deleted", false),
                new ConsumerGroupListing("expiring", false))));
        when(remaining.all()).thenReturn(KafkaFuture.completedFuture(List.of(
                new ConsumerGroupListing("expiring", false))));
        when(admin.listConsumerGroups()).thenReturn(first, remaining);
        var partial = mock(DeleteConsumerGroupsResult.class);
        var failed = new KafkaFutureImpl<Void>();
        failed.completeExceptionally(new GroupNotEmptyException("expiring"));
        when(partial.all()).thenReturn(failed);
        var success = mock(DeleteConsumerGroupsResult.class);
        when(success.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(admin.deleteConsumerGroups(List.of("already-deleted", "expiring"))).thenReturn(partial);
        when(admin.deleteConsumerGroups(List.of("expiring"))).thenReturn(success);

        LagMonitor.deleteStoppedGroups(admin);

        verify(admin).deleteConsumerGroups(List.of("already-deleted", "expiring"));
        verify(admin).deleteConsumerGroups(List.of("expiring"));
        verify(admin, times(2)).listConsumerGroups();
    }
}
