package com.example.trip_service.kafka.dto;

public record DriverArrivedEvent(
        String tripId,
        Long userId
) {}