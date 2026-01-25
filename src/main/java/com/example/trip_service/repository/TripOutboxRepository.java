package com.example.trip_service.repository;

import com.example.trip_service.entity.TripOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}