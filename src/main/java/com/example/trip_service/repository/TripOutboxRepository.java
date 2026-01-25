package com.example.trip_service.repository;

import com.example.trip_service.entity.OutboxStatus;
import com.example.trip_service.entity.TripOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TripOutboxRepository extends JpaRepository<TripOutbox, Long> {

    // 동시성 제어 : 다른 스레드/서버가 잡고 있는 행은 건너뛰고(SKIP LOCKED) 가져옴
    @Query(value = "SELECT * FROM trip_outbox " +
            "WHERE status = 'READY' " +
            "ORDER BY created_at ASC " +
            "LIMIT :limit " +
            "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<TripOutbox> findEventsForPublishing(@Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE TripOutbox t SET t.status = :status WHERE t.id IN :ids")
    void updateStatus(@Param("ids") List<Long> ids, @Param("status") OutboxStatus status);

    // 오랫동안 PUBLISHING 상태로 멈춘 건들 READY로 원복
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TripOutbox t SET t.status = :newStatus WHERE t.status = :oldStatus AND t.createdAt < :cutoffTime")
    int resetStuckEvents(@Param("oldStatus") OutboxStatus oldStatus,
                         @Param("newStatus") OutboxStatus newStatus,
                         @Param("cutoffTime") LocalDateTime cutoffTime);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TripOutbox t WHERE t.status = :status AND t.createdAt < :cutoffTime")
    int deleteOldEvents(@Param("status") OutboxStatus status,
                        @Param("cutoffTime") LocalDateTime cutoffTime);

}