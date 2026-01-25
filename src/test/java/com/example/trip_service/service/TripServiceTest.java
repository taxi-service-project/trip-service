package com.example.trip_service.service;

import com.example.trip_service.client.DriverServiceClient;
import com.example.trip_service.client.NaverMapsClient;
import com.example.trip_service.client.UserServiceClient;
import com.example.trip_service.dto.CancelTripRequest;
import com.example.trip_service.dto.CompleteTripRequest;
import com.example.trip_service.dto.TripDetailsResponse;
import com.example.trip_service.entity.Trip;
import com.example.trip_service.entity.TripOutbox;
import com.example.trip_service.entity.TripStatus;
import com.example.trip_service.exception.TripStatusConflictException;
import com.example.trip_service.repository.TripOutboxRepository;
import com.example.trip_service.repository.TripRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripServiceTest {

    @InjectMocks
    private TripService tripService;

    @Mock
    private TripRepository tripRepository;

    @Mock
    private TripOutboxRepository outboxRepository;

    @Mock
    private UserServiceClient userServiceClient;
    @Mock
    private DriverServiceClient driverServiceClient;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private NaverMapsClient naverMapsClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("기사 도착 처리 성공: 상태가 ARRIVED로 변경되고 Outbox에 이벤트가 저장된다")
    void driverArrived_Success() {
        // given
        String tripId = "test-trip-id";
        String userId = "user-uuid-101";

        Trip matchedTrip = Trip.builder()
                               .tripId(tripId)
                               .userId(userId)
                               .build();

        when(tripRepository.findByTripIdWithLock(tripId)).thenReturn(Optional.of(matchedTrip));

        // when
        tripService.driverArrived(tripId);

        // then
        assertThat(matchedTrip.getStatus()).isEqualTo(TripStatus.ARRIVED);

        ArgumentCaptor<TripOutbox> captor = ArgumentCaptor.forClass(TripOutbox.class);
        verify(outboxRepository).save(captor.capture());

        TripOutbox savedOutbox = captor.getValue();
        assertThat(savedOutbox.getAggregateId()).isEqualTo(tripId);

        assertThat(savedOutbox.getPayload()).contains(tripId);
        assertThat(savedOutbox.getPayload()).contains(userId);
    }

    @Test
    @DisplayName("기사 도착 처리 실패: 상태 충돌 시 예외 발생")
    void driverArrived_Fail_StatusConflict() {
        String tripId = "test-trip-id";
        Trip inProgressTrip = Trip.builder().tripId(tripId).build();
        ReflectionTestUtils.setField(inProgressTrip, "status", TripStatus.IN_PROGRESS);

        when(tripRepository.findByTripIdWithLock(tripId)).thenReturn(Optional.of(inProgressTrip));

        assertThrows(TripStatusConflictException.class, () -> tripService.driverArrived(tripId));
    }

    @Test
    @DisplayName("운행 시작 처리 성공: 상태가 IN_PROGRESS로 변경되고 Outbox 저장")
    void startTrip_Success() {
        String tripId = "test-trip-id";
        Trip arrivedTrip = Trip.builder().tripId(tripId).build();
        ReflectionTestUtils.setField(arrivedTrip, "status", TripStatus.ARRIVED);

        when(tripRepository.findByTripIdWithLock(tripId)).thenReturn(Optional.of(arrivedTrip));

        tripService.startTrip(tripId);

        assertThat(arrivedTrip.getStatus()).isEqualTo(TripStatus.IN_PROGRESS);
        assertThat(arrivedTrip.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("운행 종료 처리 성공: 상태가 PAYMENT_PENDING 변경 및 Outbox 저장, Redis 삭제")
    void completeTrip_Success() {
        // given
        String tripId = "test-trip-uuid-1";
        String userId = "user-uuid-101";
        String driverId = "driver-uuid-202";
        CompleteTripRequest request = new CompleteTripRequest(5000, 1200);

        Trip inProgressTrip = Trip.builder()
                                  .tripId(tripId)
                                  .userId(userId)
                                  .driverId(driverId)
                                  .build();
        ReflectionTestUtils.setField(inProgressTrip, "status", TripStatus.IN_PROGRESS);

        given(tripRepository.findByTripIdWithLock(tripId)).willReturn(Optional.of(inProgressTrip));

        // when
        tripService.completeTrip(tripId, request);

        // then
        assertThat(inProgressTrip.getStatus()).isEqualTo(TripStatus.PAYMENT_PENDING);

        ArgumentCaptor<TripOutbox> captor = ArgumentCaptor.forClass(TripOutbox.class);
        verify(outboxRepository).save(captor.capture());

        TripOutbox savedOutbox = captor.getValue();
        assertThat(savedOutbox.getAggregateId()).isEqualTo(tripId);
        assertThat(savedOutbox.getPayload()).contains("5000");

        verify(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("여정 취소 처리 성공: 상태 CANCELED 변경 및 Outbox 저장")
    void cancelTrip_Success() {
        String tripId = "test-trip-uuid-2";
        String driverId = "driver-uuid-201";
        CancelTripRequest request = new CancelTripRequest("USER");

        Trip inProgressTrip = Trip.builder().tripId(tripId).driverId(driverId).build();
        ReflectionTestUtils.setField(inProgressTrip, "status", TripStatus.IN_PROGRESS);

        when(tripRepository.findByTripIdWithLock(tripId)).thenReturn(Optional.of(inProgressTrip));

        tripService.cancelTrip(tripId, request);

        assertThat(inProgressTrip.getStatus()).isEqualTo(TripStatus.CANCELED);

        ArgumentCaptor<TripOutbox> captor = ArgumentCaptor.forClass(TripOutbox.class);
        verify(outboxRepository).save(captor.capture());

        assertThat(captor.getValue().getPayload()).contains("USER");
    }

    @Test
    @DisplayName("여정 상세 정보 조회 성공")
    void getTripDetails_Success() {
        String tripId = "test-trip-uuid-3";
        Trip trip = Trip.builder()
                        .tripId(tripId)
                        .userId("user-101")
                        .driverId("driver-201")
                        .originAddress("서울")
                        .destinationAddress("경기")
                        .matchedAt(LocalDateTime.now())
                        .userName("홍길동")
                        .driverName("김기사")
                        .vehicleModel("K5")
                        .licensePlate("12가3456")
                        .build();
        ReflectionTestUtils.setField(trip, "status", TripStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(trip, "fare", 5000);

        when(tripRepository.findByTripIdWithLock(tripId)).thenReturn(Optional.of(trip));

        TripDetailsResponse result = tripService.getTripDetails(tripId);

        assertThat(result.tripId()).isEqualTo(tripId);
        assertThat(result.status()).isEqualTo(TripStatus.IN_PROGRESS);
    }
}