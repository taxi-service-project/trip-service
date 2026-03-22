package com.example.trip_service.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    @LoadBalanced
    @Primary
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(createInternalHttpClient()));
    }

    @Bean
    public WebClient.Builder externalWebClientBuilder() {
        return WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(createExternalHttpClient()));
    }

    private HttpClient createInternalHttpClient() {
        return HttpClient.create()
                         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                         .responseTimeout(Duration.ofMillis(1200));
    }

    private HttpClient createExternalHttpClient() {
        return HttpClient.create()
                         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                         .responseTimeout(Duration.ofMillis(3500));
    }
}
