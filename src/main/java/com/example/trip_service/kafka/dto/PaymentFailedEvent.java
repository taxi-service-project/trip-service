package com.example.trip_service.kafka.dto;

public record PaymentFailedEvent(
        String tripId,
        String reason
) {}
