package com.example.trip_service.kafka;

import com.example.trip_service.entity.OutboxStatus;
import com.example.trip_service.entity.TripOutbox;
import com.example.trip_service.repository.TripOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxMessageRelay {

    private final TripOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;


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
            sendToKafka(event);
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
            updateStatus(event.getId(), OutboxStatus.READY);
        }
    }

    public void updateStatus(Long eventId, OutboxStatus status) {
        transactionTemplate.execute(tx -> {
            outboxRepository.updateStatus(List.of(eventId), status);
            return null;
        });
    }

    // 서버가 PUBLISHING 마킹 후 죽어버려서, 영원히 전송되지 못한 이벤트들을 구출
    @Scheduled(fixedRate = 60000) // 1분마다 실행
    public void rescueStuckEvents() {
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);

        transactionTemplate.execute(status -> {
            int count = outboxRepository.resetStuckEvents(OutboxStatus.PUBLISHING, OutboxStatus.READY, tenMinutesAgo);
            if (count > 0) {
                log.warn("🚨 멈춰있는(Stuck) 이벤트 {}건을 READY 상태로 복구했습니다.", count);
            }
            return null;
        });
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldEvents() {
        LocalDateTime retentionLimit = LocalDateTime.now().minusDays(3);

        transactionTemplate.execute(status -> {
            int deletedCount = outboxRepository.deleteOldEvents(OutboxStatus.DONE, retentionLimit);
            if (deletedCount > 0) {
                log.info("🧹 [Outbox Cleanup] 처리 완료된 지 3일 지난 이벤트 {}건을 삭제했습니다.", deletedCount);
            }
            return null;
        });
    }
}