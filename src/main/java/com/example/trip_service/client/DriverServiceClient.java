package com.example.trip_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class DriverServiceClient {
    private final WebClient webClient;
    private final ReactiveCircuitBreaker circuitBreaker;

    public record InternalDriverInfo(String driverId, String name, Double ratingAvg, VehicleInfo vehicle) {
        public record VehicleInfo(String licensePlate, String model) {}

        public static InternalDriverInfo unknown(String driverId) {
            return new InternalDriverInfo(driverId, "알 수 없는 기사", 0.0, new VehicleInfo("정보 없음", "정보 없음"));
        }
    }

    public DriverServiceClient(WebClient.Builder builder,
                               @Value("${services.driver-service.url}") String serviceUrl,
                               ReactiveCircuitBreakerFactory cbFactory) {
        this.webClient = builder.baseUrl(serviceUrl).build();
        this.circuitBreaker = cbFactory.create("driver-service");
    }

    public Mono<InternalDriverInfo> getDriverInfo(String driverId) {
        Mono<InternalDriverInfo> apiCall = webClient.get()
                                                    .uri("/internal/api/drivers/{driverId}", driverId)
                                                    .retrieve()
                                                    .bodyToMono(InternalDriverInfo.class)
                                                    .doOnError(e -> log.error("기사 정보 조회 실패. driverId: {}", driverId, e));

        return circuitBreaker.run(apiCall, throwable -> {
            log.warn("기사 정보 서비스 서킷 브레이커가 열렸습니다. driverId: {}. 폴백 데이터를 사용합니다.", driverId, throwable);
            return Mono.just(InternalDriverInfo.unknown(driverId));
        });
    }
}