package com.example.trip_service.kafka;

import com.example.trip_service.kafka.dto.DriverLocationUpdatedEvent;
import com.example.trip_service.kafka.dto.PaymentCompletedEvent;
import com.example.trip_service.kafka.dto.PaymentFailedEvent;
import com.example.trip_service.kafka.dto.TripMatchedEvent;
import com.example.trip_service.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@KafkaListener(topics = {"matching_events", "payment_events"}, groupId = "trip-service-group")
public class TripEventConsumer {

    private final TripService tripService;

    @KafkaHandler
    public void handleTripMatchedEvent(TripMatchedEvent event) {
        try {
            tripService.createTripFromEvent(event).block();
        } catch (Exception e) {
            log.error("배차 완료 이벤트 처리 중 오류 발생. event: {}", event, e);
        }
    }

    @KafkaHandler
    public void handlePaymentCompletedEvent(PaymentCompletedEvent event) {
        try {
            tripService.updateTripFare(event);
        } catch (Exception e) {
            log.error("결제 완료 이벤트 처리 중 오류 발생. event: {}", event, e);
        }
    }

    @KafkaHandler
    public void handlePaymentFailedEvent(PaymentFailedEvent event) {
        try {
            tripService.revertTripCompletion(event);
        } catch (Exception e) {
            log.error("결제 실패 이벤트 처리(보상 트랜잭션) 중 오류 발생. event: {}", event, e);
        }
    }

    @KafkaHandler
    public void handleDriverLocationUpdatedEvent(DriverLocationUpdatedEvent event) {
        tripService.forwardDriverLocationToPassenger(event);
    }

    @KafkaHandler(isDefault = true)
    public void handleUnknownEvent(Object event) {
        log.warn("알 수 없는 타입의 이벤트 수신: {}", event);
    }
}