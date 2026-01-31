package com.example.trip_service.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReactiveTrackingHandler implements WebSocketHandler {

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String tripId = extractTripId(session);
        String topic = "trip:location:" + tripId;

        log.info("WebFlux 소켓 연결. Redis 구독 시작: {}", topic);

        // Input: 클라이언트가 보내는 메시지 처리 (혹시 PONG을 보낸다면 로깅)
        Mono<Void> input = session.receive()
                                  // 30초 동안 아무런 메시지(PONG 포함)가 없으면 에러 발생!
                                  .timeout(Duration.ofSeconds(30))
                                  .doOnNext(msg -> {
                                      if ("PONG".equals(msg.getPayloadAsText())) {
                                          log.trace("Received PONG - 연결 생존 확인");
                                      }
                                  })
                                  .onErrorResume(e -> {
                                      // TimeoutException 발생 시 로그 찍고 종료 (소켓 끊김)
                                      log.warn("Heartbeat Timeout: 승객 연결 끊김 (TripID: {})", tripId);
                                      return Mono.empty();
                                  })
                                  .then();

        Flux<WebSocketMessage> redisFlux = reactiveRedisTemplate.listenTo(ChannelTopic.of(topic))
                                                                .map(message -> session.textMessage(message.getMessage()))
                                                                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                                                                                .doBeforeRetry(retrySignal ->
                                                                                        log.warn("승객용 Redis 구독 재시도 중... (TripID: {}, 시도: {})",
                                                                                                tripId, retrySignal.totalRetries() + 1)
                                                                                )
                                                                                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                                                                                    log.error("승객용 Redis 구독 최종 실패 (TripID: {})", tripId);
                                                                                    return retrySignal.failure();
                                                                                })
                                                                )
                                                                // 최신 위치가 중요하므로 버퍼 꽉 차면 예전 메시지 버림
                                                                .onBackpressureDrop()
                                                                .doOnError(e -> log.error("Redis 구독 치명적 에러: {}", e.getMessage()));

        // 10초마다 "PING" 전송 (Heartbeat)
        Flux<WebSocketMessage> pingFlux = Flux.interval(Duration.ofSeconds(10))
                                              .map(i -> session.textMessage("PING"));

        // Output: Redis 메시지와 Ping 메시지를 병합(Merge)해서 전송
        Mono<Void> output = session.send(Flux.merge(redisFlux, pingFlux));

        return Mono.zip(input, output)
                   .then()
                   .doFinally(signal -> log.info("소켓 연결 종료. Trip ID: {}", tripId));
    }

    private String extractTripId(WebSocketSession session) {
        URI uri = session.getHandshakeInfo().getUri();
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}