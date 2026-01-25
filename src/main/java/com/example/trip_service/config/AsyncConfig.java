package com.example.trip_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "eventPublisherExecutor")
    public Executor eventPublisherExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("Trip-Relay-");

        // 애플리케이션 종료 요청 시, 큐에 남아있는 작업과 진행 중인 작업이 완료될 때까지 대기함
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 60초가 지나도 안 끝나면 그때는 강제 종료 (무한 대기 방지)
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}