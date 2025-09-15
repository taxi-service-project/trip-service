package com.example.trip_service.entity;

import com.example.trip_service.exception.TripStatusConflictException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "trips")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, unique = true)
    private String tripId;

    @Column(nullable = false, updatable = false, name = "user_id")
    private Long userId;

    @Column(nullable = false, updatable = false, name = "driver_id")
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TripStatus status;

    @Column(nullable = false, name = "origin_address")
    private String originAddress;

    @Column(nullable = false, name = "destination_address")
    private String destinationAddress;

    private Integer fare;

    @Column(nullable = false, updatable = false, name = "matched_at")
    private LocalDateTime matchedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Builder
    public Trip(String tripId, Long userId, Long driverId, String originAddress, String destinationAddress, LocalDateTime matchedAt) {
        this.tripId = tripId;
        this.userId = userId;
        this.driverId = driverId;
        this.originAddress = originAddress;
        this.destinationAddress = destinationAddress;
        this.matchedAt = matchedAt;
        this.status = TripStatus.MATCHED;
    }

    public void updateFare(Integer fare) {
        this.fare = fare;
    }

    public void arrive() {
        if (this.status != TripStatus.MATCHED) {
            throw new TripStatusConflictException("기사가 이미 도착했거나 운행 중인 여정입니다. 현재 상태: " + this.status);
        }
        this.status = TripStatus.ARRIVED;
    }

}
