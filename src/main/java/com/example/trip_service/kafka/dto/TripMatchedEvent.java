package com.example.trip_service.kafka.dto;

import java.time.LocalDateTime;

// Matching Service가 발행하는 이벤트와 동일한 구조
public record TripMatchedEvent(
        String tripId,
        Long userId,
        Long driverId,
        Location origin,
        Location destination,
        LocalDateTime matchedAt
) {
    public record Location(Double longitude, Double latitude) {}
}