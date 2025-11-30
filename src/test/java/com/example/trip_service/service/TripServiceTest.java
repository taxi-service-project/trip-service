package com.example.trip_service.service;

import com.example.trip_service.client.DriverServiceClient;
import com.example.trip_service.client.UserServiceClient;
import com.example.trip_service.dto.CancelTripRequest;
import com.example.trip_service.dto.CompleteTripRequest;
import com.example.trip_service.dto.TripDetailsResponse;
import com.example.trip_service.entity.Trip;
import com.example.trip_service.entity.TripStatus;
import com.example.trip_service.exception.TripNotFoundException;
import com.example.trip_service.exception.TripStatusConflictException;
import com.example.trip_service.kafka.TripKafkaProducer;
import com.example.trip_service.kafka.dto.DriverArrivedEvent;
import com.example.trip_service.kafka.dto.TripCanceledEvent;
import com.example.trip_service.kafka.dto.TripCompletedEvent;
import com.example.trip_service.repository.TripRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
    private TripKafkaProducer kafkaProducer;
    @Mock
    private UserServiceClient userServiceClient;
    @Mock
    private DriverServiceClient driverServiceClient;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("기사 도착 처리 성공: 상태가 MATCHED에서 ARRIVED로 변경되고 이벤트가 발행된다")
    void driverArrived_Success() {
        // given
        String tripId = "test-trip-id";
        String userId = "user-uuid-101";

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

    @Test
    @DisplayName("운행 시작 처리 성공: 상태가 ARRIVED에서 IN_PROGRESS로 변경되고 시작 시간이 기록된다")
    void startTrip_Success() {
        // given
        String tripId = "test-trip-id";
        Trip arrivedTrip = Trip.builder().tripId(tripId).build();
        ReflectionTestUtils.setField(arrivedTrip, "status", TripStatus.ARRIVED);

        when(tripRepository.findByTripId(tripId)).thenReturn(Optional.of(arrivedTrip));

        // when
        tripService.startTrip(tripId);

        // then
        assertThat(arrivedTrip.getStatus()).isEqualTo(TripStatus.IN_PROGRESS);
        assertThat(arrivedTrip.getStartedAt()).isNotNull(); // 시작 시간이 기록되었는지 확인
    }

    @Test
    @DisplayName("운행 시작 처리 실패: 상태가 ARRIVED가 아니면 TripStatusConflictException 발생")
    void startTrip_Fail_StatusConflict() {
        // given
        String tripId = "test-trip-id";
        Trip matchedTrip = Trip.builder().tripId(tripId).build();
        ReflectionTestUtils.setField(matchedTrip, "status", TripStatus.MATCHED);

        when(tripRepository.findByTripId(tripId)).thenReturn(Optional.of(matchedTrip));

        // when & then
        assertThrows(TripStatusConflictException.class, () -> {
            tripService.startTrip(tripId);
        });
    }

    @Test
    @DisplayName("운행 종료 처리 성공: 상태가 PAYMENT_PENDING으로 변경되고 이벤트가 발행된다")
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

        given(tripRepository.findByTripId(tripId)).willReturn(Optional.of(inProgressTrip));

        // when
        tripService.completeTrip(tripId, request);

        // then
        assertThat(inProgressTrip.getStatus()).isEqualTo(TripStatus.PAYMENT_PENDING);
        assertThat(inProgressTrip.getEndedAt()).isNotNull();

        // Kafka 이벤트 발행 검증
        ArgumentCaptor<TripCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TripCompletedEvent.class);
        verify(kafkaProducer).sendTripCompletedEvent(eventCaptor.capture());

        TripCompletedEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.tripId()).isEqualTo(tripId);
        assertThat(capturedEvent.userId()).isEqualTo(userId);
        assertThat(capturedEvent.distanceMeters()).isEqualTo(5000);

        verify(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("여정 취소 처리 성공: 상태가 CANCELED로 변경되고 이벤트가 발행된다")
    void cancelTrip_Success() {
        // given
        String tripId = "test-trip-uuid-2";
        String driverId = "driver-uuid-201";

        CancelTripRequest request = new CancelTripRequest("USER");
        Trip inProgressTrip = Trip.builder().tripId(tripId).driverId(driverId).build();
        ReflectionTestUtils.setField(inProgressTrip, "status", TripStatus.IN_PROGRESS);

        when(tripRepository.findByTripId(tripId)).thenReturn(Optional.of(inProgressTrip));

        // when
        tripService.cancelTrip(tripId, request);

        // then
        assertThat(inProgressTrip.getStatus()).isEqualTo(TripStatus.CANCELED);

        ArgumentCaptor<TripCanceledEvent> eventCaptor = ArgumentCaptor.forClass(TripCanceledEvent.class);
        verify(kafkaProducer).sendTripCanceledEvent(eventCaptor.capture());

        TripCanceledEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.tripId()).isEqualTo(tripId);
        assertThat(capturedEvent.driverId()).isEqualTo(driverId);
        assertThat(capturedEvent.canceledBy()).isEqualTo("USER");
    }

    @Test
    @DisplayName("여정 취소 처리 실패: 이미 완료된 여정은 취소할 수 없다")
    void cancelTrip_Fail_AlreadyCompleted() {
        // given
        String tripId = "test-trip-id";
        CancelTripRequest request = new CancelTripRequest("DRIVER");
        Trip completedTrip = Trip.builder().tripId(tripId).build();
        ReflectionTestUtils.setField(completedTrip, "status", TripStatus.COMPLETED);

        when(tripRepository.findByTripId(tripId)).thenReturn(Optional.of(completedTrip));

        // when & then
        assertThrows(TripStatusConflictException.class, () -> {
            tripService.cancelTrip(tripId, request);
        });
    }

    @Test
    @DisplayName("여정 상세 정보 조회 성공 - 외부 호출 없이 DB 스냅샷 데이터 반환")
    void getTripDetails_Success() {
        // given
        String tripId = "test-trip-uuid-3";

        Trip trip = Trip.builder()
                        .tripId(tripId)
                        .userId("user-101")
                        .driverId("driver-201")
                        .originAddress("서울 강남구")
                        .destinationAddress("서울 서초구")
                        .matchedAt(LocalDateTime.now())
                        .userName("홍길동")
                        .driverName("김기사")
                        .vehicleModel("K5")
                        .licensePlate("12가3456")
                        .build();

        ReflectionTestUtils.setField(trip, "status", TripStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(trip, "fare", 5000);

        when(tripRepository.findByTripId(tripId)).thenReturn(Optional.of(trip));

        // when
        TripDetailsResponse result = tripService.getTripDetails(tripId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.tripId()).isEqualTo(tripId);

        assertThat(result.status()).isEqualTo(TripStatus.IN_PROGRESS);
        assertThat(result.fare()).isEqualTo(5000);

        assertThat(result.user().name()).isEqualTo("홍길동");
        assertThat(result.driver().name()).isEqualTo("김기사");
        assertThat(result.driver().model()).isEqualTo("K5");

    }
}