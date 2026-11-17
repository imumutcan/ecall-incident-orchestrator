package com.automotive.ecall.api;

import java.util.Map;

public record OperationalStatsResponse(
        long totalIncidents,
        long duplicateReplaysPrevented,
        Map<String, Long> incidentsByStatus,
        Map<String, Long> incidentsBySeverity,
        long outboxPendingCount,
        long outboxProcessedCount,
        long outboxDeadLetterCount,
        String policyCircuitBreakerState
) {
}
