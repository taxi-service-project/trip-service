package com.example.trip_service.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.ResourceAccessException;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Bean
    public DefaultErrorHandler errorHandler() {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) ->
                        new org.apache.kafka.common.TopicPartition(
                                record.topic() + ".DLT",
                                record.partition()
                        )
        );

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        errorHandler.setRetryListeners((record, ex, attempt) ->
                log.warn(
                        "Kafka 레코드 재시도 시도 #{} → topic={} / partition={} / offset={} / error={}",
                        attempt,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        ex.getMessage()
                )
        );

        // 재시도할 오류: 네트워크, DB 일시적 오류
        errorHandler.addRetryableExceptions(ResourceAccessException.class, DataAccessException.class);

        // 재시도하지 않을 오류: 데이터/코드 문제
        errorHandler.addNotRetryableExceptions(
                MessageConversionException.class, // JSON 파싱 실패
                NullPointerException.class,
                DataIntegrityViolationException.class // 중복 키, Not Null 위반 등은 즉시 DLT로 직행
        );
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);

        return factory;
    }

    // =========================================================
    // 2. [DLT용] 에러 발생 시 -> 로그만 찍고 끝냄
    // =========================================================
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> dltKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);

        DefaultErrorHandler dltErrorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    log.error("🚨 [DLT 처리 실패] DB 저장 불가. 로그만 남기고 오프셋을 넘깁니다. Payload: {}", record.value());
                },
                new FixedBackOff(0L, 0L)
        );

        dltErrorHandler.setAckAfterHandle(true);

        factory.setCommonErrorHandler(dltErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);

        return factory;
    }
}