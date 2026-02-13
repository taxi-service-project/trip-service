package com.example.trip_service.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class ReactiveKafkaConfig {

    @Bean
    public ReceiverOptions<String, Object> tripMatchedReceiverOptions(KafkaProperties kafkaProperties) {
        // 1. application.yml의 모든 설정(Deserializer 등)을 가져옵니다.
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());

        // 2. Reactive 방식에서는 수동으로 ACK를 제어해야 하므로 자동 커밋을 끕니다.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // 3. 리액티브용 별도 컨슈머 그룹 ID 설정 (기존 그룹과 겹치지 않게)
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "trip-service-reactive-group");

        return ReceiverOptions.<String, Object>create(props)
                              .subscription(Collections.singleton("matching_events"));
    }

    @Bean
    public KafkaReceiver<String, Object> tripMatchedKafkaReceiver(ReceiverOptions<String, Object> options) {
        return KafkaReceiver.create(options);
    }
}