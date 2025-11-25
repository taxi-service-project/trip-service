package com.example.trip_service.entity;

public enum TripStatus {
    MATCHED,      // 배차 완료
    ARRIVED,      // 기사 도착
    IN_PROGRESS,  // 운행 중
    PAYMENT_PENDING, // 운행은 끝났으나 결제 대기 중
    PAYMENT_FAILED,
    COMPLETED,    // 결제까지 완료
    CANCELED,      // 취소됨
}
