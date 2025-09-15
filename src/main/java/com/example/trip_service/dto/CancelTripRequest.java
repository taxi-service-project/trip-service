package com.example.trip_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CancelTripRequest(
        @NotBlank(message = "취소 주체는 필수입니다.")
        @Pattern(regexp = "USER|DRIVER", message = "취소 주체는 'USER' 또는 'DRIVER'여야 합니다.")
        String canceledBy
) {}