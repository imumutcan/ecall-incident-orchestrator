package com.automotive.ecall.outbox;

/**
 * Strategy / Port for publishing outbox events to the downstream processing pipeline.
 * Allows switching between local in-process execution and distributed Apache Kafka topics
 * via Spring Profiles without altering OutboxRelayScheduler (Open/Closed Principle).
 */
public interface IncidentEventPublisher {

    void publish(String aggregateEventId, String payloadJson, int currentAttempt, int maxRetries);
}
