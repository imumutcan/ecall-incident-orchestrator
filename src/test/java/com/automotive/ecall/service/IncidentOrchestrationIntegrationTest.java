package com.automotive.ecall.service;

import com.automotive.ecall.api.EcallCrashRequest;
import com.automotive.ecall.api.IncidentDetailResponse;
import com.automotive.ecall.domain.EcallIncidentRepository;
import com.automotive.ecall.domain.IncidentStatus;
import com.automotive.ecall.domain.OutboxEventRepository;
import com.automotive.ecall.domain.OutboxStatus;
import com.automotive.ecall.integration.SimulatedInsurancePolicyAdapter;
import com.automotive.ecall.outbox.OutboxRelayScheduler;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ecall.outbox.scheduler-enabled=false",
        "ecall.outbox.max-retries=3"
})
class IncidentOrchestrationIntegrationTest {

    @Autowired
    private EcallIngestionService ingestionService;

    @Autowired
    private OutboxRelayScheduler outboxRelayScheduler;

    @Autowired
    private SimulatedInsurancePolicyAdapter insurancePolicyAdapter;

    @Autowired
    private CircuitBreaker insurancePolicyCircuitBreaker;

    @Autowired
    private EcallIncidentRepository incidentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        incidentRepository.deleteAll();
        insurancePolicyAdapter.resetChaos();
        insurancePolicyCircuitBreaker.reset();
    }

    @Test
    @DisplayName("Sprint 2 Exit Metric 1: Normal orchestration transitions incident to DISPATCHED_COMPLETE with FNOL claim & tow reference")
    void shouldCompleteFullFnolAndDispatchOnHealthyPartnerApis() {
        String eventId = "ECALL-HAPPY-001";
        ingestionService.ingest(new EcallCrashRequest(
                eventId,
                "W1K2050401F654321",
                Instant.now(),
                45.0,
                true,
                false,
                40.9923,
                29.1244,
                90,
                1
        ));

        int processed = outboxRelayScheduler.relayPendingBatch();
        assertThat(processed).isEqualTo(1);

        IncidentDetailResponse detail = ingestionService.getByEventId(eventId);
        assertThat(detail.status()).isEqualTo(IncidentStatus.DISPATCHED_COMPLETE);
        assertThat(detail.policyNumber()).isEqualTo("POL-TR-654321");
        assertThat(detail.claimReference()).startsWith("FNOL-");
        assertThat(detail.dispatchReference()).startsWith("EMERG-TOW-");
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PROCESSED)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Sprint 2 Exit Metric 2: When Insurance Policy API fails 100%, CircuitBreaker opens and CRITICAL incidents still receive emergency tow via DISPATCHED_FALLBACK")
    void shouldOpenCircuitBreakerAndDispatchEmergencyTowFallbackDuringPolicyOutage() {
        insurancePolicyAdapter.configureChaos(0, 100); // 100% HTTP 503 failure

        for (int i = 1; i <= 6; i++) {
            String eventId = "ECALL-STORM-CRIT-" + i;
            ingestionService.ingest(new EcallCrashRequest(
                    eventId,
                    "VF1RJA0006812345" + i, // Renault Clio VIN pattern
                    Instant.now(),
                    48.5,
                    true,
                    false,
                    41.0150,
                    28.9790,
                    180,
                    2
            ));
        }

        int processed = outboxRelayScheduler.relayPendingBatch();
        assertThat(processed).isEqualTo(6);

        // Minimum number of calls is 5 with 50% threshold -> CircuitBreaker must now be OPEN
        assertThat(insurancePolicyCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        for (int i = 1; i <= 6; i++) {
            IncidentDetailResponse detail = ingestionService.getByEventId("ECALL-STORM-CRIT-" + i);
            assertThat(detail.status()).isEqualTo(IncidentStatus.DISPATCHED_FALLBACK);
            assertThat(detail.dispatchReference()).startsWith("EMERG-FB-TOW-");
            assertThat(detail.claimReference()).isNull();
        }
    }

    @Test
    @DisplayName("Sprint 2 Exit Metric 3: MODERATE crash exhausting retries during outage transitions to MANUAL_REVIEW_REQUIRED and Outbox FAILED_DLQ")
    void shouldRouteModerateCrashToManualReviewAndDlqAfterMaxRetries() throws Exception {
        insurancePolicyAdapter.configureChaos(0, 100);

        String eventId = "ECALL-MOD-DLQ-001";
        ingestionService.ingest(new EcallCrashRequest(
                eventId,
                "W1K2050401F999888",
                Instant.now(),
                18.5, // MODERATE severity (< 25.0, no airbag/rollover)
                false,
                false,
                39.9208,
                32.8541,
                270,
                1
        ));

        // Attempt 1 -> RETRY_SCHEDULED
        outboxRelayScheduler.relayPendingBatch();
        assertThat(ingestionService.getByEventId(eventId).status()).isEqualTo(IncidentStatus.RETRY_SCHEDULED);

        // Wait for backoff and run Attempt 2 -> RETRY_SCHEDULED
        Thread.sleep(150);
        outboxRelayScheduler.relayPendingBatch();
        assertThat(ingestionService.getByEventId(eventId).status()).isEqualTo(IncidentStatus.RETRY_SCHEDULED);

        // Wait for backoff and run Attempt 3 (maxRetries=3) -> MANUAL_REVIEW_REQUIRED & FAILED_DLQ
        Thread.sleep(250);
        outboxRelayScheduler.relayPendingBatch();

        IncidentDetailResponse finalDetail = ingestionService.getByEventId(eventId);
        assertThat(finalDetail.status()).isEqualTo(IncidentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.FAILED_DLQ)).isEqualTo(1L);
    }
}
