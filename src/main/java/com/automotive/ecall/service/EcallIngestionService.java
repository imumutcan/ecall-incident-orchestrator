package com.automotive.ecall.service;

import com.automotive.ecall.api.EcallCrashRequest;
import com.automotive.ecall.api.EcallIngestionResponse;
import com.automotive.ecall.api.IncidentDetailResponse;
import com.automotive.ecall.domain.EcallIncident;
import com.automotive.ecall.domain.EcallIncidentRepository;
import com.automotive.ecall.domain.OutboxEvent;
import com.automotive.ecall.domain.OutboxEventRepository;
import com.automotive.ecall.domain.SeverityLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class EcallIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EcallIngestionService.class);
    public static final String EVENT_TYPE_CRASH_RECEIVED = "ECALL_CRASH_RECEIVED";

    private final EcallIncidentRepository incidentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final SeverityCalculator severityCalculator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final AtomicLong duplicateReplaysCounter = new AtomicLong(0);

    public EcallIngestionService(
            EcallIncidentRepository incidentRepository,
            OutboxEventRepository outboxEventRepository,
            SeverityCalculator severityCalculator,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.incidentRepository = incidentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.severityCalculator = severityCalculator;
        this.objectMapper = objectMapper;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Ingests an eCall crash packet with idempotency protection.
     *
     * Why two-level idempotency check?
     * 1. Fast path: Read-only lookup catches sequential TCU retries without starting a write transaction.
     * 2. Race condition guard: If two TCU packets with the same eventId arrive at the exact same millisecond,
     *    both pass the fast path. The database UNIQUE constraint (uq_ecall_incidents_event_id) rejects the second
     *    insert with DataIntegrityViolationException. Because the write runs inside an isolated TransactionTemplate,
     *    we cleanly recover outside the rolled-back transaction and return the winning record as an idempotent replay.
     */
    public EcallIngestionResponse ingest(EcallCrashRequest request) {
        Optional<EcallIncident> existing = incidentRepository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            duplicateReplaysCounter.incrementAndGet();
            log.debug("Idempotent fast-path hit for eventId={}", request.eventId());
            return EcallIngestionResponse.from(existing.get(), true);
        }

        try {
            EcallIncident saved = requiresNewTransactionTemplate.execute(status -> persistIncidentAndOutboxAtomically(request));
            return EcallIngestionResponse.from(saved, false);
        } catch (DataIntegrityViolationException ex) {
            duplicateReplaysCounter.incrementAndGet();
            log.info("Concurrent duplicate eCall packet caught by DB unique constraint for eventId={}", request.eventId());
            EcallIncident winner = incidentRepository.findByEventId(request.eventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Unique constraint violated but existing incident not found for eventId=" + request.eventId(), ex));
            return EcallIngestionResponse.from(winner, true);
        }
    }

    private EcallIncident persistIncidentAndOutboxAtomically(EcallCrashRequest request) {
        SeverityLevel severity = severityCalculator.calculate(
                request.impactDeltaV(),
                request.airbagDeployed(),
                request.rolloverDetected()
        );

        EcallIncident incident = new EcallIncident(
                request.eventId(),
                request.vin(),
                request.crashTimestamp(),
                request.impactDeltaV(),
                request.airbagDeployed(),
                request.rolloverDetected(),
                request.latitude(),
                request.longitude(),
                request.headingDegrees(),
                request.passengersCount(),
                severity
        );

        EcallIncident savedIncident = incidentRepository.saveAndFlush(incident);

        String payloadJson = serializePayload(request, severity);
        OutboxEvent outboxEvent = new OutboxEvent(
                savedIncident.getEventId(),
                EVENT_TYPE_CRASH_RECEIVED,
                payloadJson
        );
        outboxEventRepository.saveAndFlush(outboxEvent);

        return savedIncident;
    }

    @Transactional(readOnly = true)
    public IncidentDetailResponse getByEventId(String eventId) {
        EcallIncident incident = incidentRepository.findByEventId(eventId)
                .orElseThrow(() -> new NoSuchElementException("eCall incident not found for eventId: " + eventId));
        return IncidentDetailResponse.from(incident);
    }

    public long getDuplicateReplaysPrevented() {
        return duplicateReplaysCounter.get();
    }

    private String serializePayload(EcallCrashRequest request, SeverityLevel severity) {
        try {
            return objectMapper.writeValueAsString(new OutboxIncidentPayload(
                    request.eventId(),
                    request.vin(),
                    request.crashTimestamp().toString(),
                    request.impactDeltaV(),
                    request.airbagDeployed(),
                    request.rolloverDetected(),
                    request.latitude(),
                    request.longitude(),
                    severity.name()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for eventId=" + request.eventId(), e);
        }
    }

    public record OutboxIncidentPayload(
            String eventId,
            String vin,
            String crashTimestamp,
            double impactDeltaV,
            boolean airbagDeployed,
            boolean rolloverDetected,
            double latitude,
            double longitude,
            String severity
    ) {
    }
}
