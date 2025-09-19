package com.example.trip_service.kafka.dto;

public record DriverLocationUpdatedEvent(
        String driverId,
        double latitude,
        double longitude
) {}