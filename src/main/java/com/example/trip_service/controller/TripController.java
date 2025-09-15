package com.example.trip_service.controller;

import com.example.trip_service.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    @PutMapping("/{tripId}/arrive")
    public ResponseEntity<Void> driverArrived(@PathVariable String tripId) {
        tripService.driverArrived(tripId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{tripId}/start")
    public ResponseEntity<Void> startTrip(@PathVariable String tripId) {
        tripService.startTrip(tripId);
        return ResponseEntity.noContent().build();
    }
}