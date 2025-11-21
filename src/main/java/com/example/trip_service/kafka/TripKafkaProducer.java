package com.example.trip_service.kafka;

import com.example.trip_service.kafka.dto.DriverArrivedEvent;
import com.example.trip_service.kafka.dto.TripCanceledEvent;
import com.example.trip_service.kafka.dto.TripCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripKafkaProducer {
    private static final String TOPIC = "trip_events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendDriverArrivedEvent(DriverArrivedEvent event) {
        sendSync(event.tripId(), event, "기사 도착");
    }

    public void sendTripCompletedEvent(TripCompletedEvent event) {
        sendSync(event.tripId(), event, "운행 완료");
    }

    public void sendTripCanceledEvent(TripCanceledEvent event) {
        sendSync(event.tripId(), event, "여정 취소");
    }

    private void sendSync(String key, Object event, String eventName) {
        try {
            log.info("{} 이벤트 발행 시도 -> topic: {}, tripId: {}", eventName, TOPIC, key);

            // .get()을 호출하여 카프카 ACK가 올 때까지 기다립니다.
            kafkaTemplate.send(TOPIC, key, event).get();
            log.info("{} 이벤트 발행 성공 -> tripId: {}", eventName, key);

        } catch (InterruptedException | ExecutionException e) {
            log.error("{} 이벤트 발행 실패! DB를 롤백합니다. tripId: {}, error: {}", eventName, key, e.getMessage());
            throw new RuntimeException("Kafka 전송 실패: " + eventName, e);
        }
    }
}