package com.example.trip_service.kafka.dto;

import java.time.LocalDateTime;

public record TripCompletedEvent(
        String tripId,
        Long userId,
        Integer distanceMeters,
        Integer durationSeconds,
        LocalDateTime endedAt
) {}