package com.example.trip_service.kafka;

import com.example.trip_service.kafka.dto.DriverLocationUpdatedEvent;
import com.example.trip_service.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverRecord;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class DriverLocationReactiveConsumer implements CommandLineRunner, DisposableBean {

    private final KafkaReceiver<String, String> locationKafkaReceiver;
    private final TripService tripService;
    private final ObjectMapper objectMapper;
    private Disposable subscription;

    @Override
    public void run(String... args) {
        log.info("🚀 [Reactive Location] 고성능 위치 정보 리스너 가동 (BufferTimeout 적용)");

        this.subscription = locationKafkaReceiver.receive()
                                                 // 1. 개별 레코드 파싱 (실패 시 Optional.empty 반환하여 스트림 유지)
                                                 .flatMap(record -> parseEvent(record)
                                                         .map(event -> Mono.just(new RecordContext(record, event)))
                                                         .orElseGet(Mono::empty))

                                                 // 2. 마이크로 배치화: 500개를 모으거나, 100ms가 지나면 한꺼번에 처리
                                                 // 위치 정보처럼 빈도가 높은 데이터에 효과적인 전략
                                                 .bufferTimeout(500, Duration.ofMillis(100))

                                                 // 3. 배치 단위 병렬 처리 (최대 10개의 배치를 동시에 처리)
                                                 .flatMap(batch -> {
                                                     List<DriverLocationUpdatedEvent> events = batch.stream()
                                                                                                    .map(RecordContext::event)
                                                                                                    .toList();

                                                     return tripService.forwardDriverLocationToPassengerBulk(events)
                                                                       .doOnSuccess(v -> {
                                                                           // 4. 배치 내 모든 레코드의 Offset을 한꺼번에 Acknowledge
                                                                           batch.get(batch.size() - 1).record().receiverOffset().acknowledge();
                                                                       })
                                                                       .doOnError(e -> log.error("❌ [Location Batch Error] 방송 실패: {}", e.getMessage()))
                                                                       .onErrorResume(e -> Mono.empty()); // 한 배치가 실패해도 다음 배치를 위해 스트림 유지
                                                 }, 10)
                                                 .subscribe(
                                                         null,
                                                         e -> log.error("🔥 [Critical] 위치 정보 리스너 스트림 종료됨!", e)
                                                 );
    }

    private Optional<DriverLocationUpdatedEvent> parseEvent(ReceiverRecord<String, String> record) {
        try {
            return Optional.of(objectMapper.readValue(record.value(), DriverLocationUpdatedEvent.class));
        } catch (Exception e) {
            log.warn("🗑️ [Invalid JSON] 위치 데이터 파싱 실패 스킵: {}", record.value());
            // 파싱 실패 시에도 Ack를 날려야 오프셋이 머물지 않음
            record.receiverOffset().acknowledge();
            return Optional.empty();
        }
    }

    // record와 event를 묶어서 관리하기 위한 헬퍼 레코드
    private record RecordContext(ReceiverRecord<String, String> record, DriverLocationUpdatedEvent event) {
    }

    @Override
    public void destroy() {
        if (subscription != null && !subscription.isDisposed()) {
            log.info("🛑 [Shutdown] 위치 정보 리스너를 안전하게 종료합니다.");
            subscription.dispose();
        }
    }
}