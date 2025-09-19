package com.example.trip_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class NaverMapsClient {

    private final WebClient webClient;
    private final String clientId;
    private final String clientSecret;
    private final ReactiveCircuitBreaker circuitBreaker;

    private record NaverGeocodeResponse(List<Result> results) {
        record Result(Region region, Land land) {}
        record Region(Area area1, Area area2, Area area3) {}
        record Area(String name) {}
        record Land(String name, String number1) {}
    }

    public NaverMapsClient(WebClient.Builder builder,
                           @Value("${naver.api.client-id}") String clientId,
                           @Value("${naver.api.client-secret}") String clientSecret,
                           ReactiveCircuitBreakerFactory cbFactory) {
        this.webClient = builder
                .baseUrl("https://naveropenapi.apigw.ntruss.com")
                .build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.circuitBreaker = cbFactory.create("naver-service");
    }

    public Mono<String> reverseGeocode(double longitude, double latitude) {
        String coords = String.format("%s,%s", longitude, latitude);

        Mono<String> apiCall = webClient.get()
                                        .uri("/map-reversegeocode/v2/gc?coords={coords}&output=json", coords)
                                        .header("X-NCP-APIGW-API-KEY-ID", clientId)
                                        .header("X-NCP-APIGW-API-KEY", clientSecret)
                                        .retrieve()
                                        .bodyToMono(NaverGeocodeResponse.class)
                                        .map(this::formatAddress)
                                        .doOnError(e -> log.error("네이버 지도 API 호출 실패. coords: {}", coords, e));

        return circuitBreaker.run(apiCall, throwable -> {
            log.warn("네이버 지도 API 서킷 브레이커가 열렸습니다. coords: {}. 폴백 데이터를 사용합니다.", coords, throwable);
            return Mono.just("주소 변환 실패");
        });
    }

    private String formatAddress(NaverGeocodeResponse response) {
        if (response == null || response.results() == null || response.results().isEmpty()) {
            return "알 수 없는 주소";
        }
        NaverGeocodeResponse.Result result = response.results().get(0);
        return List.of(
                           result.region().area1().name(),
                           result.region().area2().name(),
                           result.region().area3().name(),
                           result.land().name(),
                           result.land().number1()
                   ).stream()
                   .filter(s -> s != null && !s.isBlank())
                   .collect(Collectors.joining(" "));
    }
}