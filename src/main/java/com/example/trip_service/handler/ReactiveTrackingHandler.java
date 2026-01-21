package com.example.trip_service.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.net.URI;

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

        Mono<Void> input = session.receive()
                                  .doOnNext(msg -> log.trace("Msg: {}", msg.getPayloadAsText()))
                                  .then();

        // Redis 채널에서 메시지가 오면 -> 즉시 웹소켓으로 쏨
        Mono<Void> output = session.send(
                reactiveRedisTemplate.listenTo(ChannelTopic.of(topic))
                                     .map(message -> session.textMessage(message.getMessage()))
                                     .doOnError(e -> log.error("Redis 구독 에러", e))
        );

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