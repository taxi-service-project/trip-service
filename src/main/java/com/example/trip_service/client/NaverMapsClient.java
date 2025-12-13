package com.example.trip_service.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class NaverMapsClient {

    private final WebClient webClient;
    private final String clientId;
    private final String clientSecret;
    private final ReactiveCircuitBreaker circuitBreaker;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverGeocodeResponse(List<Result> results) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(Region region, Land land) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Region(Area area1, Area area2, Area area3) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Area(String name) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Land(String name, String number1, String number2) {}
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
                                        .uri(uriBuilder -> uriBuilder
                                                .path("/map-reversegeocode/v2/gc")
                                                .queryParam("coords", coords)
                                                .queryParam("output", "json")
                                                .queryParam("orders", "roadaddr")
                                                .build())
                                        .header("X-NCP-APIGW-API-KEY-ID", clientId)
                                        .header("X-NCP-APIGW-API-KEY", clientSecret)
                                        .retrieve()
                                        .bodyToMono(NaverGeocodeResponse.class)
                                        .map(this::formatAddress)
                                        .doOnError(e -> log.error("네이버 지도 API 호출 실패. coords: {}", coords, e));

        return circuitBreaker.run(apiCall, throwable -> {
            log.warn("네이버 지도 API 서킷 브레이커 발동. coords: {}", coords);
            return Mono.just("주소 확인 불가");
        });
    }

    private String formatAddress(NaverGeocodeResponse response) {
        if (response == null || response.results() == null || response.results().isEmpty()) {
            return "알 수 없는 주소";
        }

        var result = response.results().get(0);
        List<String> parts = new ArrayList<>();

        if (result.region() != null) {
            if (result.region().area1() != null) parts.add(result.region().area1().name());
            if (result.region().area2() != null) parts.add(result.region().area2().name());
            if (result.region().area3() != null) parts.add(result.region().area3().name());
        }

        if (result.land() != null) {
            parts.add(result.land().name());
            parts.add(result.land().number1());
            if (result.land().number2() != null && !result.land().number2().isBlank()) {
                parts.add("-" + result.land.number2());
            }
        }

        return parts.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining(" "));
    }
}