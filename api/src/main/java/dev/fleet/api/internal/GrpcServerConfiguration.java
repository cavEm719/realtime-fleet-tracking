package dev.fleet.api.internal;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class GrpcServerConfiguration {
    // A fixed pool bounds how much of the API copilot and resume reads can occupy (N4.3).
    @Bean(destroyMethod = "close")
    ExecutorService grpcWorkers(@Value("${internal.grpc-threads:32}") int threads) {
        return Executors.newFixedThreadPool(threads);
    }

    @Bean(destroyMethod = "shutdownNow")
    Server fleetDataServer(
            FleetDataService service,
            ExecutorService grpcWorkers,
            @Value("${internal.grpc-port:9095}") int port)
            throws IOException {
        return NettyServerBuilder.forPort(port)
                .executor(grpcWorkers)
                .maxInboundMessageSize(65536)
                .addService(service)
                .build()
                .start();
    }
}
