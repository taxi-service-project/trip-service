package com.example.trip_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class UserServiceClient {
    private final WebClient webClient;

    public record InternalUserInfo(String userId, String name) {}

    public UserServiceClient(WebClient.Builder builder,
                             @Value("${services.user-service.url}") String serviceUrl) {
        this.webClient = builder.baseUrl(serviceUrl).build();
    }

    public Mono<InternalUserInfo> getUserInfo(String userId) {
        return webClient.get()
                        .uri("/internal/api/users/{userId}", userId)
                        .retrieve()
                        .bodyToMono(InternalUserInfo.class)
                        .doOnError(e -> log.error("사용자 정보 조회 실패. userId: {}", userId, e))
                        .onErrorResume(e -> Mono.just(new InternalUserInfo(userId, "알 수 없는 사용자"))); // 조회 실패 시 기본값
    }
}