package com.example.trip_service.kafka;

import com.example.trip_service.entity.FailedEvent;
import com.example.trip_service.repository.FailedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class TripEventDltConsumer {

    private final FailedEventRepository failedEventRepository;

    @KafkaListener(
            topics = {
                    "matching_events.DLT",
                    "payment_events.DLT",
                    "driver_location_events.DLT"
            },
            groupId = "${spring.kafka.consumer.group-id}.dlt",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeDlt(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String originalDltTopic,
            @Header(value = "kafka_dlt_exception_message", required = false) String exceptionMessage
    ) {
        log.warn("[DLT 수신] 토픽: {}, 메시지: {}", originalDltTopic, message);

        if (exceptionMessage == null) {
            exceptionMessage = "Unknown Error";
        }

        FailedEvent failedEvent = FailedEvent.builder()
                                             .topic(originalDltTopic)
                                             .payload(message)
                                             .errorMessage(truncate(exceptionMessage, 1000))
                                             .build();

        failedEventRepository.save(failedEvent);
    }

    private String truncate(String str, int max) {
        if (str == null) return "";
        return str.length() > max ? str.substring(0, max) : str;
    }
}