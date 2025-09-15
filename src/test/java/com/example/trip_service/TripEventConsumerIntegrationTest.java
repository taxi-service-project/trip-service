package com.example.trip_service;

import com.example.trip_service.client.NaverMapsClient;
import com.example.trip_service.entity.Trip;
import com.example.trip_service.entity.TripStatus;
import com.example.trip_service.kafka.dto.TripMatchedEvent;
import com.example.trip_service.repository.TripRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
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
    private KafkaTemplate<String, TripMatchedEvent> kafkaTemplate;

    @Autowired
    private TripRepository tripRepository;

    @MockitoBean
    private NaverMapsClient naverMapsClient;

    @AfterEach
    void tearDown() {
        // 각 테스트가 끝난 후 DB를 깨끗하게 비움
        tripRepository.deleteAll();
    }

    @Test
    @DisplayName("TripMatched 이벤트를 수신하면, Naver API를 호출하여 주소를 변환하고 DB에 Trip을 저장한다")
    void handleTripMatchedEvent_Success() {
        // given
        double originLon = 127.0, originLat = 37.5;
        double destLon = 127.1, destLat = 37.6;

        TripMatchedEvent event = new TripMatchedEvent(
                "test-trip-id",
                101L,
                201L,
                new TripMatchedEvent.Location(originLon, originLat),
                new TripMatchedEvent.Location(destLon, destLat),
                LocalDateTime.now()
        );

        when(naverMapsClient.reverseGeocode(originLon, originLat))
                .thenReturn(Mono.just("서울시 강남구 (가짜 출발지 주소)"));
        when(naverMapsClient.reverseGeocode(destLon, destLat))
                .thenReturn(Mono.just("서울시 서초구 (가짜 목적지 주소)"));

        // when: 테스트용 Kafka 브로커에 직접 메시지를 발행
        kafkaTemplate.send("matching_events", event);

        // then: Kafka Consumer가 비동기로 메시지를 처리하고 DB에 저장할 때까지 기다린 후 검증
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Trip> trips = tripRepository.findAll();
            assertThat(trips).hasSize(1);

            Trip savedTrip = trips.get(0);
            // 1. 이벤트의 기본 정보가 잘 저장되었는지 확인
            assertThat(savedTrip.getUserId()).isEqualTo(event.userId());
            assertThat(savedTrip.getDriverId()).isEqualTo(event.driverId());
            assertThat(savedTrip.getStatus()).isEqualTo(TripStatus.MATCHED);

            // 2. Mocking한 Naver API의 응답(가짜 주소)이 잘 저장되었는지 확인
            assertThat(savedTrip.getOriginAddress()).isEqualTo("서울시 강남구 (가짜 출발지 주소)");
            assertThat(savedTrip.getDestinationAddress()).isEqualTo("서울시 서초구 (가짜 목적지 주소)");
        });
    }
}
