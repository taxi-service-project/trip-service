package com.example.trip_service.kafka;

import com.example.trip_service.kafka.dto.DriverLocationUpdatedEvent;
import com.example.trip_service.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class DriverLocationConsumer {

    private final TripService tripService;

    @KafkaListener(topics = "driver_location_events",
            groupId = "trip-service-location-group",
            containerFactory = "batchKafkaListenerContainerFactory")
    public void handleDriverLocationUpdatedEvent(List<DriverLocationUpdatedEvent> events) {
        tripService.forwardDriverLocationToPassengerBulk(events);
    }
}