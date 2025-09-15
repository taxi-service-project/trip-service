package com.example.trip_service.service;

import com.example.trip_service.client.DriverServiceClient;
import com.example.trip_service.client.NaverMapsClient;
import com.example.trip_service.client.UserServiceClient;
import com.example.trip_service.dto.CancelTripRequest;
import com.example.trip_service.dto.CompleteTripRequest;
import com.example.trip_service.dto.TripDetailsResponse;
import com.example.trip_service.entity.Trip;
import com.example.trip_service.exception.TripNotFoundException;
import com.example.trip_service.kafka.TripKafkaProducer;
import com.example.trip_service.kafka.dto.*;
import com.example.trip_service.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TripService {

    private final TripRepository tripRepository;
    private final NaverMapsClient naverMapsClient;
    private final TripKafkaProducer kafkaProducer;

    private final UserServiceClient userServiceClient;
    private final DriverServiceClient driverServiceClient;

    public Mono<Trip> createTripFromEvent(TripMatchedEvent event) {
        log.info("배차 완료 이벤트 수신. Trip ID: {}, User ID: {}, Driver ID: {}",
                event.tripId(), event.userId(), event.driverId());

        Mono<String> originAddressMono = naverMapsClient.reverseGeocode(
                event.origin().longitude(), event.origin().latitude());
        Mono<String> destinationAddressMono = naverMapsClient.reverseGeocode(
                event.destination().longitude(), event.destination().latitude());

        return Mono.zip(originAddressMono, destinationAddressMono)
                   .map(addressPair -> {
                       String originAddress = addressPair.getT1();
                       String destinationAddress = addressPair.getT2();

                       Trip trip = Trip.builder()
                                       .tripId(event.tripId())
                                       .userId(event.userId())
                                       .driverId(event.driverId())
                                       .originAddress(originAddress)
                                       .destinationAddress(destinationAddress)
                                       .matchedAt(event.matchedAt())
                                       .build();

                       return tripRepository.save(trip);
                   })
                   .doOnSuccess(trip -> log.info("새로운 여정 생성 완료. DB ID: {}", event.tripId()));
    }

    public void updateTripFare(PaymentCompletedEvent event) {
        log.info("결제 완료 이벤트 수신. Trip ID: {}, Fare: {}", event.tripId(), event.fare());

        tripRepository.findByTripId(event.tripId()).ifPresentOrElse(
                trip -> {
                    trip.updateFare(event.fare());
                    log.info("여정 요금 업데이트 완료. DB ID: {}", trip.getTripId());
                },
                () -> {
                    log.error("결제 완료 이벤트를 처리할 여정을 찾지 못했습니다. Trip ID: {}", event.tripId());
                }
        );
    }

    public void driverArrived(String tripId) {
        log.info("기사 도착 처리 시작. Trip ID: {}", tripId);

        Trip trip = tripRepository.findByTripId(tripId)
                                  .orElseThrow(() -> new TripNotFoundException("해당 tripId의 여정을 찾을 수 없습니다: " + tripId));

        trip.arrive();

        DriverArrivedEvent event = new DriverArrivedEvent(trip.getTripId(), trip.getUserId());
        kafkaProducer.sendDriverArrivedEvent(event);

        log.info("기사 도착 처리 완료. Trip DB ID: {}", trip.getId());
    }

    public void startTrip(String tripId) {
        log.info("운행 시작 처리 시작. Trip ID: {}", tripId);

        Trip trip = tripRepository.findByTripId(tripId)
                                  .orElseThrow(() -> new TripNotFoundException("해당 tripId의 여정을 찾을 수 없습니다: " + tripId));

        trip.start();

        log.info("운행 시작 처리 완료. Trip DB ID: {}", trip.getId());
    }

    public void completeTrip(String tripId, CompleteTripRequest request) {
        log.info("운행 종료 처리 시작. Trip ID: {}", tripId);

        Trip trip = tripRepository.findByTripId(tripId)
                                  .orElseThrow(() -> new TripNotFoundException("해당 tripId의 여정을 찾을 수 없습니다: " + tripId));

        LocalDateTime endedAt = trip.complete();

        TripCompletedEvent event = new TripCompletedEvent(
                trip.getTripId(),
                trip.getUserId(),
                request.distanceMeters(),
                request.durationSeconds(),
                endedAt
        );
        kafkaProducer.sendTripCompletedEvent(event);

        log.info("운행 종료 처리 완료. Trip DB ID: {}", trip.getId());
    }

    public void cancelTrip(String tripId, CancelTripRequest request) {
        log.info("여정 취소 처리 시작. Trip ID: {}, Canceled by: {}", tripId, request.canceledBy());

        Trip trip = tripRepository.findByTripId(tripId)
                                  .orElseThrow(() -> new TripNotFoundException("해당 tripId의 여정을 찾을 수 없습니다: " + tripId));

        trip.cancel();

        TripCanceledEvent event = new TripCanceledEvent(
                trip.getTripId(),
                trip.getDriverId(),
                request.canceledBy()
        );
        kafkaProducer.sendTripCanceledEvent(event);

        log.info("여정 취소 처리 완료. Trip DB ID: {}", trip.getId());
    }

    public Mono<TripDetailsResponse> getTripDetails(String tripId) {
        return Mono.fromCallable(() -> tripRepository.findByTripId(tripId)
                                                     .orElseThrow(() -> new TripNotFoundException("해당 tripId의 여정을 찾을 수 없습니다: " + tripId)))
                   .subscribeOn(Schedulers.boundedElastic()) // DB 조회는 별도 스레드에서
                   .flatMap(trip -> {
                       // 2. 사용자 정보와 기사 정보를 병렬로 조회
                       Mono<UserServiceClient.InternalUserInfo> userInfoMono = userServiceClient.getUserInfo(trip.getUserId());
                       Mono<DriverServiceClient.InternalDriverInfo> driverInfoMono = driverServiceClient.getDriverInfo(trip.getDriverId());

                       // 3. 모든 정보가 도착하면 최종 DTO로 조합
                       return Mono.zip(userInfoMono, driverInfoMono)
                                  .map(tuple -> TripDetailsResponse.of(trip, tuple.getT1(), tuple.getT2()));
                   });
    }
}