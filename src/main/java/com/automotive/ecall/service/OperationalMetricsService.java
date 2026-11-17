package com.automotive.ecall.service;

import com.automotive.ecall.api.OperationalStatsResponse;
import com.automotive.ecall.domain.EcallIncidentRepository;
import com.automotive.ecall.domain.IncidentStatus;
import com.automotive.ecall.domain.OutboxEventRepository;
import com.automotive.ecall.domain.OutboxStatus;
import com.automotive.ecall.domain.SeverityLevel;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OperationalMetricsService {

    private final EcallIncidentRepository incidentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final EcallIngestionService ingestionService;
    private final CircuitBreaker insurancePolicyCircuitBreaker;

    public OperationalMetricsService(
            EcallIncidentRepository incidentRepository,
            OutboxEventRepository outboxEventRepository,
            EcallIngestionService ingestionService,
            CircuitBreaker insurancePolicyCircuitBreaker) {
        this.incidentRepository = incidentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.ingestionService = ingestionService;
        this.insurancePolicyCircuitBreaker = insurancePolicyCircuitBreaker;
    }

    @Transactional(readOnly = true)
    public OperationalStatsResponse collectStats() {
        long totalIncidents = incidentRepository.count();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (IncidentStatus status : IncidentStatus.values()) {
            byStatus.put(status.name(), incidentRepository.countByStatus(status));
        }

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (SeverityLevel severity : SeverityLevel.values()) {
            bySeverity.put(severity.name(), incidentRepository.countBySeverity(severity));
        }

        long outboxPending = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        long outboxProcessed = outboxEventRepository.countByStatus(OutboxStatus.PROCESSED);
        long outboxDlq = outboxEventRepository.countByStatus(OutboxStatus.FAILED_DLQ);

        return new OperationalStatsResponse(
                totalIncidents,
                ingestionService.getDuplicateReplaysPrevented(),
                byStatus,
                bySeverity,
                outboxPending,
                outboxProcessed,
                outboxDlq,
                insurancePolicyCircuitBreaker.getState().name()
        );
    }
}
