package com.example.trip_service.kafka.dto;

public record TripCanceledEvent(
        String tripId,
        String driverId,
        String canceledBy
) {}