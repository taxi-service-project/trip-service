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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxMessageRelay {

    private final TripOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    @Qualifier("eventPublisherExecutor")
    private final Executor eventPublisherExecutor;

    @Scheduled(fixedDelay = 500)
    public void publishEvents() {

        List<TripOutbox> eventsToPublish = transactionTemplate.execute(status -> {
            List<TripOutbox> events = outboxRepository.findEventsForPublishing(100);

            if (events.isEmpty()) return null;

            List<Long> ids = events.stream().map(TripOutbox::getId).toList();
            outboxRepository.updateStatus(ids, OutboxStatus.PUBLISHING);

            return events;
        });

        if (eventsToPublish == null || eventsToPublish.isEmpty()) return;

        for (TripOutbox event : eventsToPublish) {
            CompletableFuture.runAsync(() -> sendToKafka(event), eventPublisherExecutor);
        }
    }

    private void sendToKafka(TripOutbox event) {
        try {
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload());

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Outbox Kafka 발행 성공. Outbox ID: {}", event.getId());
                    updateStatus(event.getId(), OutboxStatus.DONE);
                } else {
                    log.error("Outbox Kafka 발행 실패. Outbox ID: {}, Error: {}", event.getId(), ex.getMessage());
                    updateStatus(event.getId(), OutboxStatus.READY);
                }
            });

        } catch (Exception e) {
            log.error("Outbox Relay 내부 에러 발생", e);
        }
    }

    public void updateStatus(Long eventId, OutboxStatus status) {
        transactionTemplate.execute(tx -> {
            outboxRepository.updateStatus(List.of(eventId), status);
            return null;
        });
    }
}