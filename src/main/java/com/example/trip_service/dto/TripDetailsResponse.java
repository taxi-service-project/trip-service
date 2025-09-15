package com.example.trip_service.dto;

import com.example.trip_service.client.DriverServiceClient.InternalDriverInfo;
import com.example.trip_service.client.UserServiceClient.InternalUserInfo;
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
    public record UserInfo(Long id, String name) {}
    public record DriverInfo(Long id, String name, Double ratingAvg, String licensePlate, String model) {}

    public static TripDetailsResponse of(Trip trip, InternalUserInfo userInfo, InternalDriverInfo driverInfo) {
        return TripDetailsResponse.builder()
                                  .tripId(trip.getTripId())
                                  .status(trip.getStatus())
                                  .originAddress(trip.getOriginAddress())
                                  .destinationAddress(trip.getDestinationAddress())
                                  .fare(trip.getFare())
                                  .matchedAt(trip.getMatchedAt())
                                  .startedAt(trip.getStartedAt())
                                  .endedAt(trip.getEndedAt())
                                  .user(new UserInfo(userInfo.id(), userInfo.name()))
                                  .driver(new DriverInfo(driverInfo.id(), driverInfo.name(), driverInfo.ratingAvg(),
                                          driverInfo.vehicle().licensePlate(), driverInfo.vehicle().model()))
                                  .build();
    }
}