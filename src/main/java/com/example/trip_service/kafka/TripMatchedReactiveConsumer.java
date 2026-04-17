package com.example.trip_service.kafka;

import com.example.trip_service.kafka.dto.TripMatchedEvent;
import com.example.trip_service.service.TripService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class TripMatchedReactiveConsumer implements CommandLineRunner, DisposableBean {

    private final KafkaReceiver<String, String> tripMatchedKafkaReceiver;
    private final TripService tripService;
    private final ReactiveKafkaProducerTemplate<String, String> reactiveKafkaProducerTemplate;
    private final ObjectMapper objectMapper;
    private Disposable subscription;

    @Override
    public void run(String... args) {
        log.info("🚀 [Reactive Kafka] 배차 이벤트 리스너 시작 (Concurrency: 256, Manual Parsing)");

        this.subscription = tripMatchedKafkaReceiver.receive()
                                                    .flatMap(record -> {
                                                        return processRecord(record)
                                                                // 메인 스트림 구독 끊김을 방지하는 최상위 방어막 추가
                                                                .onErrorResume(e -> {
                                                                    log.error("🔥 [Critical] 처리 중 잡히지 않은 최상위 예외 발생. 스트림 보호를 위해 스킵합니다. Payload: {}", record.value(), e);
                                                                    return Mono.empty();
                                                                })
                                                                .doOnSuccess(v -> record.receiverOffset().acknowledge());
                                                    }, 256)
                                                    .subscribe(
                                                            null,
                                                            e -> log.error("💀 [Fatal Error] Consumer 구독이 비정상 종료되었습니다. 앱 재시작이 필요합니다.", e)
                                                    );
    }

    private Mono<Void> processRecord(ReceiverRecord<String, String> record) {
        TripMatchedEvent event;

        try {
            String jsonPayload = record.value();
            event = objectMapper.readValue(jsonPayload, TripMatchedEvent.class);
        } catch (JsonProcessingException e) {
            log.error("🗑️ [Bad Request] JSON 파싱 실패. 메시지를 스킵합니다. Payload: {}", record.value());
            return Mono.empty();
        } catch (Exception e) {
            return Mono.error(e);
        }

        return Mono.defer(() -> tripService.createTripFromEvent(event))
                   .then()
                   .doOnSubscribe(s -> log.debug("⚡ [Start] TripID={}", event.tripId()))
                   .retryWhen(Retry.backoff(3, Duration.ofMillis(1000))
                                   .transientErrors(true)
                                   .doBeforeRetry(signal -> log.warn("🔄 [Retry] ({}/3) Error: {}",
                                           signal.totalRetries() + 1, signal.failure().getMessage())))
                   .onErrorResume(e -> handleFailure(record, e));
    }

    private Mono<Void> handleFailure(ReceiverRecord<String, String> record, Throwable e) {
        String dltTopic = record.topic() + ".DLT";
        log.error("🚨 [Final Fail] 재시도 초과. DLT 전송 시도. Topic={}, Error={}", dltTopic, e.getMessage());

        return reactiveKafkaProducerTemplate.send(dltTopic, record.key(), record.value())
                                            .flatMap(senderResult -> {
                                                log.info("[DLT Sent] DLT 전송 성공. Offset을 커밋합니다.");
                                                return Mono.empty();
                                            })
                                            .onErrorResume(dltEx -> {
                                                log.error("[FATAL] DLT 전송 실패! 데이터 유실 방지용 로그 기록.\nKEY: {}\nPAYLOAD: {}\nERROR: {}",
                                                        record.key(), record.value(), dltEx.getMessage());
                                                return Mono.empty();
                                            })
                                            .then();
    }

    @Override
    public void destroy() {
        if (subscription != null && !subscription.isDisposed()) {
            log.info("🛑 [Shutdown] Reactive Consumer 구독을 안전하게 종료합니다.");
            subscription.dispose();
        }
    }
}