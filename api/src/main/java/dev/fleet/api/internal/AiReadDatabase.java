package dev.fleet.api.internal;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * Copilot queries run on their own read-only pool, normally against a MySQL replica, so they
 * neither write nor take connections from position processing.
 */
public final class AiReadDatabase implements AutoCloseable {
    /** The replica is too far behind the primary to answer from. */
    public static final class Stale extends RuntimeException {
        Stale(String message) {
            super(message);
        }
    }

    private final HikariDataSource pool;
    private final JdbcTemplate sql;
    private final org.springframework.transaction.support.TransactionTemplate snapshots;
    private final long maxLagMicros;

    public AiReadDatabase(HikariDataSource pool, long maxLagMicros) {
        pool.setReadOnly(true);
        this.pool = pool;
        this.sql = new JdbcTemplate(pool);
        snapshots =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool));
        snapshots.setReadOnly(true);
        snapshots.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        this.maxLagMicros = maxLagMicros;
    }

    static AiReadDatabase create(
            DataSourceProperties primary,
            String url,
            String username,
            String password,
            int poolSize,
            long maxLagMicros) {
        var pool = new HikariDataSource();
        pool.setPoolName("ai-read");
        pool.setJdbcUrl(url.isBlank() ? primary.determineUrl() : url);
        pool.setUsername(username.isBlank() ? primary.determineUsername() : username);
        pool.setPassword(password.isBlank() ? primary.determinePassword() : password);
        pool.setMaximumPoolSize(poolSize);
        pool.setConnectionTimeout(2000);
        return new AiReadDatabase(pool, maxLagMicros);
    }

    public JdbcTemplate sql() {
        return sql;
    }

    /** Several statements (a total and its groups) read one consistent snapshot. */
    public <T> T snapshot(java.util.function.Supplier<T> reads) {
        return snapshots.execute(status -> reads.get());
    }

    /** Time of the newest primary heartbeat visible here; fails when the replica lags too far. */
    public Instant dataAsOf() {
        var row =
                sql.queryForList(
                        "SELECT beat_at, TIMESTAMPDIFF(MICROSECOND, beat_at, CURRENT_TIMESTAMP(6))"
                                + " FROM replication_heartbeat WHERE id = 1");
        if (row.isEmpty()) throw new Stale("Replication heartbeat unavailable");
        var values = row.getFirst().values().toArray();
        long lag = ((Number) values[1]).longValue();
        if (lag > maxLagMicros) throw new Stale("Replica is " + lag / 1000 + " ms behind");
        return ((java.sql.Timestamp) values[0]).toInstant();
    }

    @Override
    public void close() {
        pool.close();
    }
}
