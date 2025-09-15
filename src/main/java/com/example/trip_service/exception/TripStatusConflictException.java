package com.example.trip_service.exception;

public class TripStatusConflictException extends RuntimeException {
    public TripStatusConflictException(String message) {
        super(message);
    }
}