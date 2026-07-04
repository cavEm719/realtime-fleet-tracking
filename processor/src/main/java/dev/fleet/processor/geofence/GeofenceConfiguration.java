package dev.fleet.processor.geofence;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(GeofenceSettings.class)
public class GeofenceConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(GeofenceConfiguration.class);

    @Bean
    FenceIndex fenceIndex(FenceMapper mapper) {
        var index = new FenceIndex();
        var fences = mapper.loadActive();
        fences.forEach(index::put);
        LOG.info("Loaded {} circular fences into the per-company index", fences.size());
        return index;
    }

    @Bean
    TransitionSink transitionSink(
            StateMapper mapper,
            PlatformTransactionManager manager,
            MeterRegistry metrics) {
        return new TransactionalTransitionWriter(mapper, new TransactionTemplate(manager), metrics);
    }

    @Bean
    GeofenceEngine geofenceEngine(
            FenceIndex index, GeofenceSettings settings, StateMapper loader) {
        return new GeofenceEngine(index, settings, loader);
    }

    @Bean
    FenceCache fenceCache(FenceMapper mapper, FenceIndex index, MeterRegistry metrics) {
        return new FenceCache(mapper, index, metrics);
    }
}
