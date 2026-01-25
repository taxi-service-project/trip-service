package com.example.trip_service.service;

import com.example.trip_service.entity.FailedEvent;
import com.example.trip_service.entity.FailedEventStatus;
import com.example.trip_service.repository.FailedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class FailedEventReplayService {

    private final FailedEventRepository failedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    private static final int CHUNK_SIZE = 1000;

    public int retryAllByTopic(String targetTopic) {
        int totalProcessed = 0;
        boolean hasNext = true;

        log.info("🚀 [Bulk Retry] 토픽({}) 재발행 시작...", targetTopic);

        while (hasNext) {
            Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
            Slice<FailedEvent> slice = failedEventRepository.findAllByTopicAndStatus(
                    targetTopic,
                    FailedEventStatus.PENDING,
                    pageable
            );

            List<FailedEvent> events = slice.getContent();
            if (events.isEmpty()) break;

            List<Long> successIds = new ArrayList<>();

            for (FailedEvent event : events) {
                try {
                    // Key를 반드시 포함해서 보내야 원래 파티션으로 가서 순서가 유지됨
                    if (event.getKafkaKey() != null) {
                        kafkaTemplate.send(event.getTopic(), event.getKafkaKey(), event.getPayload()).get();
                    } else {
                        kafkaTemplate.send(event.getTopic(), event.getPayload()).get();
                    }

                    successIds.add(event.getId());

                } catch (Exception e) {
                    log.error("❌ 재발행 개별 실패 (ID: {}). 건너뜁니다.", event.getId(), e);
                }
            }

            if (!successIds.isEmpty()) {
                transactionTemplate.execute(status -> {
                    failedEventRepository.updateStatusToResolved(successIds);
                    return null;
                });
                totalProcessed += successIds.size();
            }
            hasNext = slice.hasNext();
        }

        log.info("✅ [Bulk Retry] 완료. 총 {}건 재발행됨.", totalProcessed);
        return totalProcessed;
    }

}