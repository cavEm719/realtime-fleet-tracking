package dev.fleet.api;

import dev.fleet.common.FaultInjector;
import dev.fleet.common.Topics;
import dev.fleet.common.outbox.OutboxRelay;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class OutboxConfiguration {
    @Bean
    @ConditionalOnMissingBean(FaultInjector.class)
    FaultInjector faultInjector() {
        return FaultInjector.disabled();
    }

    @Bean
    OutboxRelay outboxRelay(
            JdbcTemplate sql,
            PlatformTransactionManager manager,
            KafkaTemplate<String, String> kafka,
            FaultInjector faults,
            @Value("${outbox.batch-size:100}") int batchSize) {
        return new OutboxRelay(
                sql,
                new TransactionTemplate(manager),
                kafka,
                faults,
                Topics.FENCE_CHANGES,
                batchSize);
    }

    @Bean
    RelaySchedule relaySchedule(OutboxRelay relay) {
        return new RelaySchedule(relay);
    }

    public record RelaySchedule(OutboxRelay relay) {
        @Scheduled(
                fixedDelayString = "${outbox.poll-interval-ms:1000}",
                initialDelayString = "${outbox.initial-delay-ms:1000}")
        public void poll() {
            relay.poll();
        }
    }

    @Bean
    NewTopic fenceChangesTopic() {
        return TopicBuilder.name(Topics.FENCE_CHANGES)
                .partitions(Topics.POSITION_PARTITIONS)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic fenceEventsTopic() {
        return TopicBuilder.name(Topics.FENCE_EVENTS)
                .partitions(Topics.POSITION_PARTITIONS)
                .replicas(1)
                .build();
    }
}
