package com.example.trip_service.dto;

import com.example.trip_service.entity.Trip;
import com.example.trip_service.entity.TripStatus;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record TripDetailsResponse(
        String tripId,
        TripStatus status,
        String originAddress,
        String destinationAddress,
        Integer fare,
        LocalDateTime matchedAt,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        UserInfo user,
        DriverInfo driver
) {
    public record UserInfo(String userId, String name) {}
    public record DriverInfo(String driverId, String name, String licensePlate, String model) {}

    public static TripDetailsResponse fromEntity(Trip trip) {
        return TripDetailsResponse.builder()
                                  .tripId(trip.getTripId())
                                  .status(trip.getStatus())
                                  .originAddress(trip.getOriginAddress())
                                  .destinationAddress(trip.getDestinationAddress())
                                  .fare(trip.getFare())
                                  .matchedAt(trip.getMatchedAt())
                                  .startedAt(trip.getStartedAt())
                                  .endedAt(trip.getEndedAt())
                                  .user(new UserInfo(
                                          trip.getUserId(),
                                          trip.getUserName()
                                  ))
                                  .driver(new DriverInfo(
                                          trip.getDriverId(),
                                          trip.getDriverName(),
                                          trip.getLicensePlate(),
                                          trip.getVehicleModel()
                                  ))
                                  .build();
    }
}