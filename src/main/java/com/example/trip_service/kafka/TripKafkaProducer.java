package com.example.trip_service.kafka;

import com.example.trip_service.kafka.dto.DriverArrivedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripKafkaProducer {
    private static final String TOPIC = "trip_events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendDriverArrivedEvent(DriverArrivedEvent event) {
        log.info("기사 도착 이벤트 발행 -> topic: {}, tripId: {}", TOPIC, event.tripId());
        kafkaTemplate.send(TOPIC, event);
    }
}