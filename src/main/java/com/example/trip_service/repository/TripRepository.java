package com.example.trip_service.repository;

import com.example.trip_service.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findByTripId(String tripId);
}
