package com.example.trip_service.entity;

public enum TripStatus {
    MATCHED,      // 배차 완료
    ARRIVED,      // 기사 도착
    IN_PROGRESS,  // 운행 중
    COMPLETED,    // 운행 완료
    CANCELED      // 취소됨
}
