package dev.fleet.common.outbox;

import dev.fleet.common.FaultInjector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Both applications run this relay; row locks prevent concurrent ownership of a claimed row. */
public final class OutboxRelay {
    private record Row(long id, String topic, String key, String payload) {}

    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;
    private final KafkaTemplate<String, String> kafka;
    private final FaultInjector faults;
    private final int batchSize;
    private final String topic;

    public OutboxRelay(
            JdbcTemplate sql,
            TransactionTemplate transactions,
            KafkaTemplate<String, String> kafka,
            FaultInjector faults,
            String topic,
            int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("outbox batch size must be positive");
        }
        this.sql = sql;
        this.transactions = transactions;
        this.kafka = kafka;
        this.faults = faults;
        this.batchSize = batchSize;
        this.topic = java.util.Objects.requireNonNull(topic);
    }

    public void poll() {
        transactions.executeWithoutResult(
                status -> {
                    List<Row> rows =
                            sql.query(
                                    """
                                    SELECT id, topic, msg_key, CAST(payload AS CHAR) AS payload
                                    FROM outbox WHERE topic = ? AND published_at IS NULL
                                    ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                                    """,
                                    (result, row) ->
                                            new Row(
                                                    result.getLong("id"),
                                                    result.getString("topic"),
                                                    result.getString("msg_key"),
                                                    result.getString("payload")),
                                    topic,
                                    batchSize);
                    if (rows.isEmpty()) {
                        return;
                    }
                    publishBatch(rows);
                    faults.hit(FaultInjector.Point.AFTER_PUBLISH_BEFORE_MARK);
                    String placeholders = String.join(",", Collections.nCopies(rows.size(), "?"));
                    sql.update(
                            "UPDATE outbox SET published_at = CURRENT_TIMESTAMP(3) WHERE id IN ("
                                    + placeholders
                                    + ")",
                            rows.stream().map(Row::id).toArray());
                });
    }

    private void publishBatch(List<Row> rows) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var futures = new ArrayList<CompletableFuture<?>>();
        try {
            for (var row : rows) {
                if (System.nanoTime() >= deadline) {
                    throw new java.util.concurrent.TimeoutException(
                            "Outbox send deadline exceeded");
                }
                futures.add(
                        kafka.send(
                                new org.apache.kafka.clients.producer.ProducerRecord<>(
                                        row.topic(), row.key(), row.payload())));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new java.util.concurrent.TimeoutException(
                        "Outbox acknowledgement deadline exceeded");
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Outbox publish interrupted", error);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Outbox batch publish failed; all rows remain pending", error);
        }
    }
}
