package com.example.trip_service.kafka.dto;

import java.time.LocalDateTime;

public record TripMatchedEvent(
        String tripId,
        String userId,
        String driverId,
        Location origin,
        Location destination,
        LocalDateTime matchedAt
) {
    public record Location(Double longitude, Double latitude) {}
}