package com.automotive.ecall.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    public static final String POLICY_SERVICE_CB = "insurancePolicyService";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            @Value("${ecall.resilience.failure-rate-threshold:50.0}") float failureRateThreshold,
            @Value("${ecall.resilience.sliding-window-size:10}") int slidingWindowSize,
            @Value("${ecall.resilience.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${ecall.resilience.wait-duration-in-open-state-ms:5000}") long waitDurationInOpenStateMs) {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofMillis(waitDurationInOpenStateMs))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public CircuitBreaker insurancePolicyCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(POLICY_SERVICE_CB);
    }
}
