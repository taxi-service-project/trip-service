package com.example.trip_service;

import com.example.trip_service.client.NaverMapsClient;
import com.example.trip_service.entity.Trip;
import com.example.trip_service.entity.TripStatus;
import com.example.trip_service.kafka.dto.PaymentCompletedEvent;
import com.example.trip_service.kafka.dto.TripMatchedEvent;
import com.example.trip_service.repository.TripRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

@SpringBootTest
// 테스트용 Kafka 브로커를 띄우고, 'matching_events' 토픽을 생성
@EmbeddedKafka(partitions = 1, topics = {"matching_events"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
@TestPropertySource(properties = {
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=*"
})
class TripEventConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private TripRepository tripRepository;

    @MockitoBean
    private NaverMapsClient naverMapsClient;

    @AfterEach
    void tearDown() {
        tripRepository.deleteAll();
    }

    @Test
    @DisplayName("TripMatched 이벤트를 수신하면, Naver API를 호출하여 주소를 변환하고 DB에 Trip을 저장한다")
    void handleTripMatchedEvent_Success() {
        // given
        double originLon = 127.0, originLat = 37.5;
        double destLon = 127.1, destLat = 37.6;

        String tripId = "trip-uuid-1";
        String userId = "user-uuid-101";
        String driverId = "driver-uuid-201";

        TripMatchedEvent event = new TripMatchedEvent(
                tripId, userId, driverId,
                new TripMatchedEvent.Location(originLon, originLat),
                new TripMatchedEvent.Location(destLon, destLat),
                LocalDateTime.now()
        );

        when(naverMapsClient.reverseGeocode(originLon, originLat))
                .thenReturn(Mono.just("서울시 강남구 (가짜 출발지 주소)"));
        when(naverMapsClient.reverseGeocode(destLon, destLat))
                .thenReturn(Mono.just("서울시 서초구 (가짜 목적지 주소)"));

        // when
        kafkaTemplate.send("matching_events", event);

        // then
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Trip> trips = tripRepository.findAll();
            assertThat(trips).hasSize(1);

            Trip savedTrip = trips.get(0);
            assertThat(savedTrip.getTripId()).isEqualTo(tripId);
            assertThat(savedTrip.getUserId()).isEqualTo(userId);
            assertThat(savedTrip.getDriverId()).isEqualTo(driverId);
            assertThat(savedTrip.getStatus()).isEqualTo(TripStatus.MATCHED);
            assertThat(savedTrip.getOriginAddress()).isEqualTo("서울시 강남구 (가짜 출발지 주소)");
        });
    }

    @Test
    @DisplayName("PaymentCompleted 이벤트를 수신하면 기존 Trip 엔티티의 요금(fare)이 업데이트되어야 한다")
    void handlePaymentCompletedEvent_Success() {
        // given
        String targetTripId = "trip-uuid-for-payment";
        String userId = "user-uuid-1";
        String driverId = "driver-uuid-1";

        Trip existingTrip = Trip.builder()
                                .tripId(targetTripId)
                                .userId(userId)
                                .driverId(driverId)
                                .originAddress("출발지")
                                .destinationAddress("목적지")
                                .matchedAt(LocalDateTime.now())
                                .build();
        tripRepository.save(existingTrip);

        PaymentCompletedEvent event = new PaymentCompletedEvent(targetTripId, 15000);

        // when
        kafkaTemplate.send("payment_events", event);

        // then
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Trip updatedTrip = tripRepository.findByTripId(targetTripId).orElseThrow();
            assertThat(updatedTrip.getFare()).isNotNull();
            assertThat(updatedTrip.getFare()).isEqualTo(15000);
        });
    }
}
