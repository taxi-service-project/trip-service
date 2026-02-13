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
import com.example.trip_service.exception.TripNotFoundException;
import com.example.trip_service.kafka.dto.*;
import com.example.trip_service.repository.TripOutboxRepository;
import com.example.trip_service.repository.TripRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripService {
    private static final String DRIVER_TRIP_KEY_PREFIX = "driver:trip:";
    private static final String KAFKA_TOPIC = "trip_events";

    private final TripRepository tripRepository;
    private final NaverMapsClient naverMapsClient;
    private final UserServiceClient userServiceClient;
    private final DriverServiceClient driverServiceClient;
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TripOutboxRepository outboxRepository;

    public Mono<Trip> createTripFromEvent(TripMatchedEvent event) {
        log.info("배차 완료 이벤트 수신. Trip ID: {}", event.tripId());
        return Mono.fromCallable(() -> tripRepository.findByTripId(event.tripId()))
                   .subscribeOn(Schedulers.boundedElastic())
                   .flatMap(optionalTrip -> {
                       if (optionalTrip.isPresent()) {
                           log.info("이미 처리된 Trip ID 입니다. (중복 처리 생략): {}", event.tripId());
                           return Mono.just(optionalTrip.get());
                       }
                       return processNewTrip(event);
                   });
    }

    private Mono<Trip> processNewTrip(TripMatchedEvent event) {
        Mono<String> originMono = naverMapsClient.reverseGeocode(
                                                         event.origin().longitude(), event.origin().latitude())
                                                 .onErrorReturn("출발지 주소 확인 불가");

        Mono<String> destMono = naverMapsClient.reverseGeocode(
                                                       event.destination().longitude(), event.destination().latitude())
                                               .onErrorReturn("목적지 주소 확인 불가");

        Mono<UserServiceClient.InternalUserInfo> userMono =
                userServiceClient.getUserInfo(event.userId());

        Mono<DriverServiceClient.InternalDriverInfo> driverMono =
                driverServiceClient.getDriverInfo(event.driverId());

        return Mono.zip(originMono, destMono, userMono, driverMono)
                   .flatMap(tuple -> {
                       String originAddress = tuple.getT1();
                       String destinationAddress = tuple.getT2();
                       var userInfo = tuple.getT3();
                       var driverInfo = tuple.getT4();

                       Trip trip = Trip.builder()
                                       .tripId(event.tripId())
                                       .userId(event.userId())
                                       .driverId(event.driverId())
                                       .originAddress(originAddress)
                                       .destinationAddress(destinationAddress)
                                       .matchedAt(event.matchedAt())
                                       .userName(userInfo.name())
                                       .driverName(driverInfo.name())
                                       .vehicleModel(driverInfo.vehicle().model())
                                       .licensePlate(driverInfo.vehicle().licensePlate())
                                       .build();

                       return Mono.fromCallable(() -> {
                                      try {
                                          return tripRepository.save(trip);
                                      } catch (DataIntegrityViolationException e) {
                                          // 아주 짧은 찰나에 동시 요청이 들어왔을 때를 대비한 2차 방어선
                                          log.warn("동시성 이슈로 인한 중복 Trip ID 감지 (무시): {}", event.tripId());
                                          return tripRepository.findByTripId(event.tripId()).orElse(trip);
                                      }
                                  })
                                  .subscribeOn(Schedulers.boundedElastic())
                                  .flatMap(savedTrip -> {
                                      String key = DRIVER_TRIP_KEY_PREFIX + event.driverId();
                                      return reactiveRedisTemplate.opsForValue()
                                                                  .set(key, savedTrip.getTripId(), Duration.ofHours(3))
                                                                  .doOnSuccess(v -> log.info("Redis 캐싱 완료. Driver: {}", event.driverId()))
                                                                  .onErrorResume(e -> {
                                                                      log.error("Redis 캐싱 실패. Error: {}", e.getMessage());
                                                                      return Mono.empty();
                                                                  })
                                                                  .thenReturn(savedTrip);
                                  });
                   });
    }

    @Transactional
    @Retryable(
            retryFor = {
                    PessimisticLockingFailureException.class, // 락 타임아웃
                    DeadlockLoserDataAccessException.class,   // 데드락
                    TransientDataAccessException.class        // DB 네트워크/연결 일시적 장애
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 500)
    )
    public void driverArrived(String tripId) {
        Trip trip = getTripOrThrow(tripId);

        if (trip.getStatus() == TripStatus.ARRIVED) {
            log.info("중복 요청(재시도) 감지됨. 성공 응답 반환. TripID: {}", tripId);
            // 서버 처리는 성공했는데, 응답이 가는 도중에 네트워크가 끊길때 : 이미 Outbox에도 저장되었을 것이므로 아무것도 안 하고 리턴
            return;
        }

        trip.arrive();
        DriverArrivedEvent event = new DriverArrivedEvent(trip.getTripId(), trip.getUserId());
        saveToOutbox(tripId, event);

        log.info("기사 도착 처리 완료 (Outbox 저장됨): {}", tripId);
    }

    @Transactional
    @Retryable(
            retryFor = {
                    PessimisticLockingFailureException.class,
                    DeadlockLoserDataAccessException.class,
                    TransientDataAccessException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 500)
    )
    public void startTrip(String tripId) {
        Trip trip = getTripOrThrow(tripId);

        if (trip.getStatus() == TripStatus.IN_PROGRESS) {
            log.info("중복 요청(재시도) 감지됨. 성공 응답 반환. TripID: {}", tripId);
            return;
        }

        trip.start();
        log.info("운행 시작 처리 완료: {}", tripId);
    }

    // 기사님이 [운행 종료] 버튼 누름
    @Transactional
    @Retryable(
            retryFor = {
                    PessimisticLockingFailureException.class,
                    DeadlockLoserDataAccessException.class,
                    TransientDataAccessException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 500)
    )
    public void completeTrip(String tripId, CompleteTripRequest request) {
        Trip trip = getTripOrThrow(tripId);

        if (trip.getStatus() == TripStatus.COMPLETED) {
            log.info("중복 요청(재시도) 감지됨. 성공 응답 반환. TripID: {}", tripId);
            return;
        }

        LocalDateTime endedAt = trip.complete();

        TripCompletedEvent event = new TripCompletedEvent(
                trip.getTripId(), trip.getUserId(), trip.getDriverId(),
                request.distanceMeters(), request.durationSeconds(), endedAt
        );
        saveToOutbox(tripId, event);

        scheduleRedisKeyDeletion(trip.getDriverId());

        log.info("운행 종료 요청 처리 완료 (결제 대기 중, Outbox 저장됨): {}", tripId);
    }

    // PaymentService가 "결제 성공" 이벤트 보냄 -> Consumer가 호출
    @Transactional
    public void handlePaymentSuccess(PaymentCompletedEvent event) {
        tripRepository.findByTripId(event.tripId()).ifPresentOrElse(
                trip -> {
                    trip.confirmPayment();
                    trip.updateFare(event.fare());
                    log.info("최종 여정 완료 (결제 성공): {}", trip.getTripId());
                },
                () -> log.error("여정 미발견: {}", event.tripId())
        );
    }

    @Transactional
    @Retryable(
            retryFor = {
                    PessimisticLockingFailureException.class,
                    DeadlockLoserDataAccessException.class,
                    TransientDataAccessException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 500)
    )
    public void cancelTrip(String tripId, CancelTripRequest request) {
        Trip trip = getTripOrThrow(tripId);

        if (trip.getStatus() == TripStatus.CANCELED) {
            log.info("중복 요청(재시도) 감지됨. 성공 응답 반환. TripID: {}", tripId);
            return;
        }

        trip.cancel();

        TripCanceledEvent event = new TripCanceledEvent(
                trip.getTripId(), trip.getDriverId(), request.canceledBy()
        );
        saveToOutbox(tripId, event);

        scheduleRedisKeyDeletion(trip.getDriverId());

        log.info("여정 취소 처리 완료 (Outbox 저장됨): {}", tripId);
    }

    @Transactional(readOnly = true)
    public TripDetailsResponse getTripDetails(String tripId) {
        Trip trip = getTripOrThrow(tripId);
        return TripDetailsResponse.fromEntity(trip);
    }

    @Transactional
    public void revertTripCompletion(PaymentFailedEvent event) {
        tripRepository.findByTripId(event.tripId()).ifPresentOrElse(
                trip -> {
                    trip.revertCompletion();
                    log.info("보상 트랜잭션(롤백) 완료: {}", trip.getId());
                },
                () -> log.error("보상 트랜잭션 대상 미발견: {}", event.tripId())
        );
    }

    public Mono<Void> forwardDriverLocationToPassengerBulk(List<DriverLocationUpdatedEvent> events) {
        if (events == null || events.isEmpty()) return Mono.empty();

        // 1. 모든 Driver ID에 해당하는 Redis Key 리스트 생성
        List<String> keys = events.stream()
                                  .map(event -> DRIVER_TRIP_KEY_PREFIX + event.driverId())
                                  .toList();

        // 2. MultiGet으로 모든 Trip ID를 한 번에 조회 (네트워크 1회)
        return reactiveRedisTemplate.opsForValue().multiGet(keys)
                                    .flatMapMany(tripIds -> {
                                        // events와 조회된 tripIds를 인덱스로 매칭하여 처리
                                        return Flux.range(0, events.size())
                                                   .flatMap(i -> {
                                                       DriverLocationUpdatedEvent event = events.get(i);
                                                       String tripId = (String) tripIds.get(i);

                                                       // 매칭되는 TripId가 없으면 방송 스킵
                                                       if (tripId == null) return Mono.empty();

                                                       // 방송할 채널 이름 결정 (예: trip:location:12345)
                                                       String topic = "trip:location:" + tripId;
                                                       try {
                                                           String messageJson = objectMapper.writeValueAsString(event);
                                                           // Redis Pub/Sub 방송 (비동기 병렬 실행)
                                                           return reactiveRedisTemplate.convertAndSend(topic, messageJson);
                                                       } catch (JsonProcessingException e) {
                                                           return Mono.error(e);
                                                       }
                                                   });
                                    })
                                    .then() // 모든 전송이 완료될 때까지 대기
                                    .doOnSuccess(v -> log.debug("📍 위치 정보 {}개 방송 완료", events.size()))
                                    .onErrorResume(e -> {
                                        log.error("❌ 위치 방송 중 오류 발생", e);
                                        return Mono.empty(); // 에러가 나도 다음 배치를 위해 삼킴
                                    });
    }

    public boolean isDriverOnTrip(String driverId) {
        return tripRepository.existsByDriverIdAndStatus(driverId, TripStatus.IN_PROGRESS);
    }

    private Trip getTripOrThrow(String tripId) {
        return tripRepository.findByTripIdWithLock(tripId)
                             .orElseThrow(() -> new TripNotFoundException("여정 정보 없음: " + tripId));
    }

    private void saveToOutbox(String tripId, Object event) {
        TripOutbox outbox = TripOutbox.builder()
                                      .aggregateId(tripId)
                                      .topic(KAFKA_TOPIC)
                                      .payload(toJson(event))
                                      .build();

        outboxRepository.save(outbox); // 같은 트랜잭션 내에서 저장됨
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("JSON 변환 오류", e);
            throw new RuntimeException("JSON 변환 실패", e);
        }
    }

    private void deleteRedisKeySafely(String driverId) {
        try {
            redisTemplate.delete(DRIVER_TRIP_KEY_PREFIX + driverId);
        } catch (Exception e) {
            log.error("Redis 키 삭제 실패. Driver ID: {}", driverId, e);
        }
    }

    private void scheduleRedisKeyDeletion(String driverId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteRedisKeySafely(driverId);
                    log.info("트랜잭션 커밋 후 Redis 키 삭제 실행");
                }
            });
        } else {
            deleteRedisKeySafely(driverId);
        }
    }
}