package com.example.trip_service.repository;

import com.example.trip_service.entity.Trip;
import com.example.trip_service.entity.TripStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findByTripId(String tripId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0")})
    @Query("select t from Trip t where t.tripId = :tripId")
    Optional<Trip> findByTripIdWithLock(@Param("tripId") String tripId);

    // 이 기사가 'IN_PROGRESS' 상태인 여정을 가지고 있는지 확인 (존재하면 true)
    boolean existsByDriverIdAndStatus(String driverId, TripStatus status);
}
