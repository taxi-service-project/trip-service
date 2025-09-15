package com.example.trip_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class DriverServiceClient {
    private final WebClient webClient;

    public record InternalDriverInfo(Long id, String name, Double ratingAvg, VehicleInfo vehicle) {
        public record VehicleInfo(String licensePlate, String model) {}
    }

    public DriverServiceClient(WebClient.Builder builder,
                               @Value("${services.driver-service.url}") String serviceUrl) {
        this.webClient = builder.baseUrl(serviceUrl).build();
    }

    public Mono<InternalDriverInfo> getDriverInfo(Long driverId) {
        return webClient.get()
                        .uri("/internal/api/drivers/{driverId}", driverId)
                        .retrieve()
                        .bodyToMono(InternalDriverInfo.class)
                        .doOnError(e -> log.error("기사 정보 조회 실패. driverId: {}", driverId, e))
                        .onErrorResume(e -> Mono.empty());
    }
}