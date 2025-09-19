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
public class UserServiceClient {
    private final WebClient webClient;
    private final ReactiveCircuitBreaker circuitBreaker;

    public record InternalUserInfo(String userId, String name) {}

    public UserServiceClient(WebClient.Builder builder,
                             @Value("${services.user-service.url}") String serviceUrl,
                             ReactiveCircuitBreakerFactory cbFactory) {
        this.webClient = builder.baseUrl(serviceUrl).build();
        this.circuitBreaker = cbFactory.create("user-service");
    }

    public Mono<InternalUserInfo> getUserInfo(String userId) {
        Mono<InternalUserInfo> apiCall = webClient.get()
                                                  .uri("/internal/api/users/{userId}", userId)
                                                  .retrieve()
                                                  .bodyToMono(InternalUserInfo.class)
                                                  .doOnError(e -> log.error("사용자 정보 조회 실패. userId: {}", userId, e));

        return circuitBreaker.run(apiCall, throwable -> {
            log.warn("사용자 정보 서비스 서킷 브레이커가 열렸습니다. userId: {}. 폴백 데이터를 사용합니다.", userId, throwable);
            return Mono.just(new InternalUserInfo(userId, "알 수 없는 사용자"));
        });
    }
}