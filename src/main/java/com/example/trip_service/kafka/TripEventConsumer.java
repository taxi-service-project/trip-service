package com.example.trip_service.kafka;

import com.example.trip_service.kafka.dto.*;
import com.example.trip_service.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@KafkaListener(topics = {"matching_events", "payment_events", "driver_location_events"}, groupId = "trip-service-group")
public class TripEventConsumer {

    private final TripService tripService;

    @KafkaHandler
    public void handleTripMatchedEvent(TripMatchedEvent event) {
        log.info("Consumer: 배차 이벤트 수신 - TripID: {}", event.tripId());
        tripService.createTripFromEvent(event).block();
    }

    @KafkaHandler
    public void handlePaymentCompletedEvent(PaymentCompletedEvent event) {
        log.info("Consumer: 결제 완료 이벤트 수신 - TripID: {}", event.tripId());
        tripService.updateTripFare(event);
    }

    @KafkaHandler
    public void handlePaymentFailedEvent(PaymentFailedEvent event) {
        log.info("Consumer: 결제 실패 이벤트 수신 - TripID: {}", event.tripId());
        tripService.revertTripCompletion(event);
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