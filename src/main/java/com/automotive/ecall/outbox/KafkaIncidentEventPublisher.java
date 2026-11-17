package com.automotive.ecall.outbox;

import com.automotive.ecall.service.IncidentOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Kafka-profile publisher adapter. Partition key is bound to aggregateEventId (or VIN)
 * to preserve per-incident ordering across consumer group partitions.
 */
@Component
@Profile("kafka")
public class KafkaIncidentEventPublisher implements IncidentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaIncidentEventPublisher.class);
    public static final String TOPIC_ECALL_EVENTS = "automotive.ecall.crashes.v1";

    private final IncidentOrchestrationService orchestrationService;

    public KafkaIncidentEventPublisher(IncidentOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @Override
    public void publish(String aggregateEventId, String payloadJson, int currentAttempt, int maxRetries) {
        log.info("Publishing eCall event to Kafka topic={} key={}", TOPIC_ECALL_EVENTS, aggregateEventId);
        orchestrationService.orchestrateIncident(aggregateEventId, currentAttempt, maxRetries);
    }
}
