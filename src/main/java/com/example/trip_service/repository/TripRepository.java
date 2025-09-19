package com.example.trip_service.repository;

import com.example.trip_service.entity.Trip;
import com.example.trip_service.entity.TripStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findByTripId(String tripId);

    Optional<Trip> findFirstByDriverIdAndStatusIn(String driverId, List<TripStatus> statuses);

}
