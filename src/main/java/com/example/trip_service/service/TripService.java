package com.example.trip_service.service;

import com.example.trip_service.client.NaverMapsClient;
import com.example.trip_service.entity.Trip;
import com.example.trip_service.kafka.dto.PaymentCompletedEvent;
import com.example.trip_service.kafka.dto.TripMatchedEvent;
import com.example.trip_service.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TripService {

    private final TripRepository tripRepository;
    private final NaverMapsClient naverMapsClient;

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
}