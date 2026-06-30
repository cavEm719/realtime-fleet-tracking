package dev.fleet.processor;

import com.fasterxml.jackson.core.JsonProcessingException;

import dev.fleet.common.Topics;
import dev.fleet.processor.geofence.GeofenceEngine;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;
import java.util.Collection;

@Configuration
public class KafkaConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConfiguration.class);

    @Bean
    NewTopic positionsDeadLetterTopic() {
        return TopicBuilder.name(Topics.POSITIONS_DLT)
                .partitions(Topics.POSITION_PARTITIONS)
                .replicas(1)
                .build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumers,
            KafkaTemplate<String, String> producer,
            GeofenceEngine geofences) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.setBatchListener(true);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setConsumerRebalanceListener(rebalanceListener(geofences));
        factory.setCommonErrorHandler(errorHandler(producer));
        return factory;
    }

    private DefaultErrorHandler errorHandler(KafkaTemplate<String, String> producer) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        producer,
                        (record, error) ->
                                new TopicPartition(Topics.POSITIONS_DLT, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(15));

        var backOff = new ExponentialBackOff(1000, 2);
        backOff.setMaxInterval(10000);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        var errors = new DefaultErrorHandler(recoverer, backOff);
        errors.addNotRetryableExceptions(
                IllegalArgumentException.class, JsonProcessingException.class);
        errors.setCommitRecovered(true);
        errors.setAckAfterHandle(false);
        return errors;
    }

    ConsumerAwareRebalanceListener rebalanceListener(GeofenceEngine geofences) {
        return new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedBeforeCommit(
                    Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                geofences.dropPartitions(partitions);
                LOG.info("Revoked {}; synchronous batch commits have finished", partitions);
            }

            @Override
            public void onPartitionsAssigned(
                    Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                LOG.info("Assigned {}; geofence state starts in memory as OUTSIDE", partitions);
            }

            @Override
            public void onPartitionsLost(
                    Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                geofences.dropPartitions(partitions);
                LOG.warn("Lost {}; no offsets committed from this callback", partitions);
            }
        };
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> fenceChangeFactory(
            ConsumerFactory<String, String> consumers) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new ExponentialBackOff(1000, 2)));
        return factory;
    }
}
