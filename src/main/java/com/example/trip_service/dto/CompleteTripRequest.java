package com.example.trip_service.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CompleteTripRequest(
        @NotNull(message = "운행 거리는 필수입니다.")
        @Positive(message = "운행 거리는 0보다 커야 합니다.")
        Integer distanceMeters,

        @NotNull(message = "운행 시간은 필수입니다.")
        @Positive(message = "운행 시간은 0보다 커야 합니다.")
        Integer durationSeconds
) {}