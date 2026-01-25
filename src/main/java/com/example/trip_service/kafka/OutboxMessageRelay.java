package com.example.trip_service.kafka;

import com.example.trip_service.entity.OutboxStatus;
import com.example.trip_service.entity.TripOutbox;
import com.example.trip_service.repository.TripOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxMessageRelay {

    private final TripOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Qualifier("eventPublisherExecutor")
    private final Executor eventPublisherExecutor;

    @Scheduled(fixedDelay = 500)
    @Transactional // 락 헤제를 위함
    public void publishEvents() {
        List<TripOutbox> events = outboxRepository.findEventsForPublishing(100);

        if (events.isEmpty()) return;

        for (TripOutbox event : events) {
            event.changeStatus(OutboxStatus.PUBLISHING);
        }

        for (TripOutbox event : events) {
            CompletableFuture.runAsync(() -> sendToKafka(event), eventPublisherExecutor);
        }
    }

    private void sendToKafka(TripOutbox event) {
        try {
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload());

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Kafka 발행 성공. Outbox ID: {}", event.getId());
                    updateStatus(event.getId(), OutboxStatus.DONE);
                } else {
                    // 실패 시: 로그 남김 -> 다음 Polling 때 다시 시도됨 (Retry 자동 효과)
                    log.error("Kafka 발행 실패. Outbox ID: {}, Error: {}", event.getId(), ex.getMessage());
                }
            });

        } catch (Exception e) {
            log.error("Relay 내부 에러 발생", e);
        }
    }

    //  콜백은 비동기 스레드에서 돌므로 새로운 트랜잭션을 열어야 함
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(Long eventId, OutboxStatus status) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.changeStatus(status);
        });
    }
}