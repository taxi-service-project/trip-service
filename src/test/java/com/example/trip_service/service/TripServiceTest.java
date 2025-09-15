package com.example.trip_service.service;

import com.example.trip_service.entity.Trip;
import com.example.trip_service.entity.TripStatus;
import com.example.trip_service.exception.TripNotFoundException;
import com.example.trip_service.exception.TripStatusConflictException;
import com.example.trip_service.kafka.TripKafkaProducer;
import com.example.trip_service.kafka.dto.DriverArrivedEvent;
import com.example.trip_service.repository.TripRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripServiceTest {

    @InjectMocks
    private TripService tripService;

    @Mock
    private TripRepository tripRepository;
    @Mock
    private TripKafkaProducer kafkaProducer;

    @Test
    @DisplayName("기사 도착 처리 성공: 상태가 MATCHED에서 ARRIVED로 변경되고 이벤트가 발행된다")
    void driverArrived_Success() {
        // given: 초기 조건 설정
        String tripId = "test-trip-id";
        long userId = 101L;

        Trip matchedTrip = Trip.builder()
                               .tripId(tripId)
                               .userId(userId)
                               .build();
        when(tripRepository.findByTripId(tripId)).thenReturn(Optional.of(matchedTrip));

        // when: 로직 실행
        tripService.driverArrived(tripId);

        // then: 결과 검증
        assertThat(matchedTrip.getStatus()).isEqualTo(TripStatus.ARRIVED);

        ArgumentCaptor<DriverArrivedEvent> eventCaptor = ArgumentCaptor.forClass(DriverArrivedEvent.class);
        verify(kafkaProducer).sendDriverArrivedEvent(eventCaptor.capture());
        DriverArrivedEvent capturedEvent = eventCaptor.getValue();

        assertThat(capturedEvent.tripId()).isEqualTo(tripId);
        assertThat(capturedEvent.userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("기사 도착 처리 실패: 여정의 상태가 MATCHED가 아니면 TripStatusConflictException 발생")
    void driverArrived_Fail_StatusConflict() {
        // given: 초기 조건 설정
        String tripId = "test-trip-id";
        Trip inProgressTrip = Trip.builder().tripId(tripId).build();

        ReflectionTestUtils.setField(inProgressTrip, "status", TripStatus.IN_PROGRESS);

        when(tripRepository.findByTripId(tripId)).thenReturn(Optional.of(inProgressTrip));

        // when & then: 예외 발생 검증
        assertThrows(TripStatusConflictException.class, () -> {
            tripService.driverArrived(tripId);
        });
    }

    @Test
    @DisplayName("기사 도착 처리 실패: tripId에 해당하는 여정이 없으면 TripNotFoundException 발생")
    void driverArrived_Fail_TripNotFound() {
        // given: 초기 조건 설정
        String tripId = "non-existent-trip-id";
        when(tripRepository.findByTripId(tripId)).thenReturn(Optional.empty());

        // when & then: 예외 발생 검증
        assertThrows(TripNotFoundException.class, () -> {
            tripService.driverArrived(tripId);
        });
    }
}