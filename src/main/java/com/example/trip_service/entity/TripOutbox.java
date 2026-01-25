package com.example.trip_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "trip_outbox", indexes = @Index(name = "idx_outbox_status_created", columnList = "status, createdAt"))
public class TripOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregateId; // Trip ID

    private String topic;       // 발행할 토픽 이름

    @Lob
    private String payload;     // 이벤트 내용 (JSON)

    @Enumerated(EnumType.STRING)
    private OutboxStatus status; // READY, DONE

    private LocalDateTime createdAt;

    @Builder
    public TripOutbox(String aggregateId, String topic, String payload) {
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
        this.status = OutboxStatus.READY;
        this.createdAt = LocalDateTime.now();
    }

    public void changeStatus(OutboxStatus status) {
        this.status = status;
    }

}