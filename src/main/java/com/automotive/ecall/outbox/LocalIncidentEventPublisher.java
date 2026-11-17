package com.automotive.ecall.outbox;

import com.automotive.ecall.service.IncidentOrchestrationService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!kafka")
public class LocalIncidentEventPublisher implements IncidentEventPublisher {

    private final IncidentOrchestrationService orchestrationService;

    public LocalIncidentEventPublisher(IncidentOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @Override
    public void publish(String aggregateEventId, String payloadJson, int currentAttempt, int maxRetries) {
        orchestrationService.orchestrateIncident(aggregateEventId, currentAttempt, maxRetries);
    }
}
