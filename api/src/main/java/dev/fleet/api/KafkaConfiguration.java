package dev.fleet.api;

import dev.fleet.common.Topics;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfiguration {
    @Bean
    NewTopic positionsTopic() {
        return TopicBuilder.name(Topics.POSITIONS)
                .partitions(Topics.POSITION_PARTITIONS)
                .replicas(1)
                .build();
    }
}
