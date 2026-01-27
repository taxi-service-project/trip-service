package com.example.trip_service.controller;

import com.example.trip_service.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/drivers")
@RequiredArgsConstructor
public class InternalTripController {

    private final TripService tripService;

    @GetMapping("/{driverId}/in-progress")
    public ResponseEntity<Boolean> isDriverInProgress(@PathVariable String driverId) {

        boolean isDriving = tripService.isDriverOnTrip(driverId);

        return ResponseEntity.ok(isDriving);
    }
}