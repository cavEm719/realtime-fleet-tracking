package dev.fleet.api.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
public class AiReadConfiguration {
    // Not a DataSource bean, so Spring Boot keeps auto-configuring the primary pool.
    @Bean(destroyMethod = "close")
    AiReadDatabase aiReadDatabase(
            DataSourceProperties primary,
            @Value("${fleet.ai-read.url:}") String url,
            @Value("${fleet.ai-read.username:}") String username,
            @Value("${fleet.ai-read.password:}") String password,
            @Value("${fleet.ai-read.pool-size:16}") int poolSize,
            @Value("${fleet.ai-read.max-lag:60s}") java.time.Duration maxLag) {
        return AiReadDatabase.create(
                primary, url, username, password, poolSize, maxLag.toNanos() / 1000);
    }

    @Bean
    Heartbeat replicationHeartbeat(JdbcTemplate primary) {
        return new Heartbeat(primary);
    }

    public record Heartbeat(JdbcTemplate primary) {
        @Scheduled(fixedRate = 1000)
        public void beat() {
            primary.update(
                    "UPDATE replication_heartbeat SET beat_at = CURRENT_TIMESTAMP(6) WHERE id = 1");
        }
    }
}
