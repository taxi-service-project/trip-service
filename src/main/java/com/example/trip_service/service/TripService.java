package com.example.trip_service.service;

import com.example.trip_service.client.DriverServiceClient;
import com.example.trip_service.client.NaverMapsClient;
import com.example.trip_service.client.UserServiceClient;
import com.example.trip_service.dto.CancelTripRequest;
import com.example.trip_service.dto.CompleteTripRequest;
import com.example.trip_service.dto.TripDetailsResponse;
import com.example.trip_service.entity.Trip;
import com.example.trip_service.exception.TripNotFoundException;
import com.example.trip_service.handler.TrackingWebSocketHandler;
import com.example.trip_service.kafka.TripKafkaProducer;
import com.example.trip_service.kafka.dto.*;
import com.example.trip_service.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripService {

    private final TripRepository tripRepository;
    private final NaverMapsClient naverMapsClient;
    private final TripKafkaProducer kafkaProducer;
    private final UserServiceClient userServiceClient;
    private final DriverServiceClient driverServiceClient;
    private final TrackingWebSocketHandler trackingWebSocketHandler;

    private final StringRedisTemplate redisTemplate;
    private static final String DRIVER_TRIP_KEY_PREFIX = "driver:trip:";

    public Mono<Trip> createTripFromEvent(TripMatchedEvent event) {
        log.info("배차 완료 이벤트 수신. Trip ID: {}", event.tripId());

        Mono<String> originMono = naverMapsClient.reverseGeocode(
                                                         event.origin().longitude(), event.origin().latitude())
                                                 .onErrorReturn("출발지 주소 확인 불가");

        Mono<String> destMono = naverMapsClient.reverseGeocode(
                                                       event.destination().longitude(), event.destination().latitude())
                                               .onErrorReturn("목적지 주소 확인 불가");

        return Mono.zip(originMono, destMono)
                   .flatMap(tuple -> {
                       String originAddress = tuple.getT1();
                       String destinationAddress = tuple.getT2();

                       Trip trip = Trip.builder()
                                       .tripId(event.tripId())
                                       .userId(event.userId())
                                       .driverId(event.driverId())
                                       .originAddress(originAddress)
                                       .destinationAddress(destinationAddress)
                                       .matchedAt(event.matchedAt())
                                       .build();

                       return Mono.fromCallable(() -> tripRepository.save(trip))
                                  .subscribeOn(Schedulers.boundedElastic())
                                  .doOnSuccess(savedTrip -> {
                                      String key = DRIVER_TRIP_KEY_PREFIX + event.driverId();
                                      redisTemplate.opsForValue().set(key, savedTrip.getTripId(), 24, TimeUnit.HOURS);
                                      log.info("새로운 여정 생성 및 Redis 캐싱 완료. DB ID: {}", savedTrip.getTripId());
                                  });
                   });
    }

    @Transactional
    public void updateTripFare(PaymentCompletedEvent event) {
        tripRepository.findByTripId(event.tripId()).ifPresentOrElse(
                trip -> {
                    trip.updateFare(event.fare());
                    log.info("여정 요금 업데이트 완료: {}", trip.getTripId());
                },
                () -> log.error("여정 미발견: {}", event.tripId())
        );
    }

    @Transactional
    public void driverArrived(String tripId) {
        Trip trip = getTripOrThrow(tripId);
        trip.arrive();
        kafkaProducer.sendDriverArrivedEvent(new DriverArrivedEvent(trip.getTripId(), trip.getUserId()));
        log.info("기사 도착 처리 완료: {}", tripId);
    }

    @Transactional
    public void startTrip(String tripId) {
        Trip trip = getTripOrThrow(tripId);
        trip.start();
        log.info("운행 시작 처리 완료: {}", tripId);
    }

    @Transactional
    public void completeTrip(String tripId, CompleteTripRequest request) {
        Trip trip = getTripOrThrow(tripId);
        LocalDateTime endedAt = trip.complete();

        TripCompletedEvent event = new TripCompletedEvent(
                trip.getTripId(), trip.getUserId(),
                request.distanceMeters(), request.durationSeconds(), endedAt
        );
        kafkaProducer.sendTripCompletedEvent(event);

        redisTemplate.delete(DRIVER_TRIP_KEY_PREFIX + trip.getDriverId());

        log.info("운행 종료 처리 완료: {}", tripId);
    }

    @Transactional
    public void cancelTrip(String tripId, CancelTripRequest request) {
        Trip trip = getTripOrThrow(tripId);
        trip.cancel();

        TripCanceledEvent event = new TripCanceledEvent(
                trip.getTripId(), trip.getDriverId(), request.canceledBy()
        );
        kafkaProducer.sendTripCanceledEvent(event);

        redisTemplate.delete(DRIVER_TRIP_KEY_PREFIX + trip.getDriverId());

        log.info("여정 취소 처리 완료: {}", tripId);
    }

    @Transactional(readOnly = true)
    public Mono<TripDetailsResponse> getTripDetails(String tripId) {
        return Mono.fromCallable(() -> getTripOrThrow(tripId))
                   .subscribeOn(Schedulers.boundedElastic())
                   .flatMap(trip -> {
                       Mono<UserServiceClient.InternalUserInfo> userInfoMono =
                               userServiceClient.getUserInfo(trip.getUserId());
                       Mono<DriverServiceClient.InternalDriverInfo> driverInfoMono =
                               driverServiceClient.getDriverInfo(trip.getDriverId());

                       return Mono.zip(userInfoMono, driverInfoMono)
                                  .map(tuple -> TripDetailsResponse.of(trip, tuple.getT1(), tuple.getT2()));
                   });
    }

    @Transactional
    public void revertTripCompletion(PaymentFailedEvent event) {
        tripRepository.findByTripId(event.tripId()).ifPresentOrElse(
                trip -> {
                    trip.revertCompletion();
                    log.info("여정 상태 롤백 완료: {}", trip.getId());
                },
                () -> log.error("보상 트랜잭션 실패 (여정 미발견): {}", event.tripId())
        );
    }

    public void forwardDriverLocationToPassenger(DriverLocationUpdatedEvent event) {
        String key = DRIVER_TRIP_KEY_PREFIX + event.driverId();

        String currentTripId = redisTemplate.opsForValue().get(key);

        if (currentTripId != null) {
            trackingWebSocketHandler.sendLocationUpdate(currentTripId, event);
        }
    }

    private Trip getTripOrThrow(String tripId) {
        return tripRepository.findByTripId(tripId)
                             .orElseThrow(() -> new TripNotFoundException("해당 tripId의 여정을 찾을 수 없습니다: " + tripId));
    }
}