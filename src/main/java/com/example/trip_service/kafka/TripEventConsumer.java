package com.example.trip_service.kafka;

import com.example.trip_service.kafka.dto.PaymentCompletedEvent;
import com.example.trip_service.kafka.dto.TripMatchedEvent;
import com.example.trip_service.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TripEventConsumer {

    private final TripService tripService;

    @KafkaListener(topics = "matching_events", groupId = "trip-service-group")
    public void handleTripMatchedEvent(TripMatchedEvent event) {
        try {
            tripService.createTripFromEvent(event).block();
        } catch (Exception e) {
            log.error("배차 완료 이벤트 처리 중 오류 발생. event: {}", event, e);
        }
    }

    @KafkaListener(topics = "payment_events", groupId = "trip-service-group")
    public void handlePaymentCompletedEvent(PaymentCompletedEvent event) {
        try {
            tripService.updateTripFare(event);
        } catch (Exception e) {
            log.error("결제 완료 이벤트 처리 중 오류 발생. event: {}", event, e);
        }
    }
}