package com.example.trip_service.controller;

import com.example.trip_service.dto.CompleteTripRequest;
import com.example.trip_service.service.TripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @PutMapping("/{tripId}/complete")
    public ResponseEntity<Void> completeTrip(@PathVariable String tripId,
                                             @Valid @RequestBody CompleteTripRequest request) {
        tripService.completeTrip(tripId, request);
        return ResponseEntity.noContent().build();
    }
}