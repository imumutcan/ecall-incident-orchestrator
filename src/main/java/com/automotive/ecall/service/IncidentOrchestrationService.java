package com.automotive.ecall.service;

import com.automotive.ecall.domain.EcallIncident;
import com.automotive.ecall.domain.EcallIncidentRepository;
import com.automotive.ecall.domain.IncidentStatus;
import com.automotive.ecall.integration.ExternalServiceException;
import com.automotive.ecall.integration.PolicyVerificationPort;
import com.automotive.ecall.integration.PolicyVerificationPort.PolicyFnolResult;
import com.automotive.ecall.integration.RoadsideDispatchPort;
import com.automotive.ecall.integration.RoadsideDispatchPort.DispatchResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(IncidentOrchestrationService.class);

    private final EcallIncidentRepository incidentRepository;
    private final PolicyVerificationPort policyVerificationPort;
    private final RoadsideDispatchPort roadsideDispatchPort;
    private final CircuitBreaker insurancePolicyCircuitBreaker;

    public IncidentOrchestrationService(
            EcallIncidentRepository incidentRepository,
            PolicyVerificationPort policyVerificationPort,
            RoadsideDispatchPort roadsideDispatchPort,
            CircuitBreaker insurancePolicyCircuitBreaker) {
        this.incidentRepository = incidentRepository;
        this.policyVerificationPort = policyVerificationPort;
        this.roadsideDispatchPort = roadsideDispatchPort;
        this.insurancePolicyCircuitBreaker = insurancePolicyCircuitBreaker;
    }

    /**
     * Orchestrates policy verification, FNOL claim creation, and roadside/emergency dispatch.
     *
     * Key Domain Rule:
     * If the Insurance Policy Service fails or its Circuit Breaker is OPEN:
     * - CRITICAL and HIGH severity crashes immediately trigger Roadside/Emergency Dispatch via fallback
     *   (status = DISPATCHED_FALLBACK) because passenger safety cannot wait for legacy insurance systems.
     * - MODERATE severity crashes are scheduled for exponential backoff retry via the Outbox (status = RETRY_SCHEDULED).
     */
    @Transactional(noRollbackFor = {ExternalServiceException.class, CallNotPermittedException.class})
    public void orchestrateIncident(String eventId, int currentAttempt, int maxRetries) {
        EcallIncident incident = incidentRepository.findByEventId(eventId)
                .orElseThrow(() -> new IllegalStateException("Incident not found during orchestration: " + eventId));

        if (incident.getStatus() == IncidentStatus.DISPATCHED_COMPLETE
                || incident.getStatus() == IncidentStatus.DISPATCHED_FALLBACK
                || incident.getStatus() == IncidentStatus.MANUAL_REVIEW_REQUIRED) {
            log.debug("Incident {} already in terminal state {}, skipping orchestration", eventId, incident.getStatus());
            return;
        }

        incident.markProcessing();

        try {
            PolicyFnolResult policyResult = insurancePolicyCircuitBreaker.executeSupplier(() ->
                    policyVerificationPort.verifyPolicyAndOpenClaim(
                            incident.getEventId(),
                            incident.getVin(),
                            incident.getCrashTimestamp(),
                            incident.getImpactDeltaV(),
                            incident.getLatitude(),
                            incident.getLongitude()
                    )
            );

            if (!policyResult.policyActive()) {
                incident.markManualReviewRequired("Vehicle VIN has no active insurance coverage at crash timestamp");
                incidentRepository.save(incident);
                return;
            }

            DispatchResult dispatch = roadsideDispatchPort.dispatchAssistance(
                    incident.getEventId(),
                    incident.getVin(),
                    incident.getSeverity(),
                    incident.getLatitude(),
                    incident.getLongitude(),
                    incident.getHeadingDegrees(),
                    incident.getPassengersCount(),
                    false
            );

            incident.markDispatchedComplete(
                    policyResult.policyNumber(),
                    policyResult.claimReference(),
                    dispatch.dispatchReference()
            );
            incidentRepository.save(incident);

        } catch (CallNotPermittedException | ExternalServiceException ex) {
            handlePolicyServiceFailure(incident, currentAttempt, maxRetries, ex);
        }
    }

    private void handlePolicyServiceFailure(
            EcallIncident incident,
            int currentAttempt,
            int maxRetries,
            RuntimeException cause) {

        String reason = (cause instanceof CallNotPermittedException)
                ? "CircuitBreaker OPEN on insurancePolicyService: " + cause.getMessage()
                : cause.getMessage();

        if (incident.getSeverity().requiresImmediateEmergencyDispatch()) {
            log.warn("Policy service unavailable for {} incident {}. Executing immediate emergency fallback dispatch. Cause: {}",
                    incident.getSeverity(), incident.getEventId(), reason);

            DispatchResult fallbackDispatch = roadsideDispatchPort.dispatchAssistance(
                    incident.getEventId(),
                    incident.getVin(),
                    incident.getSeverity(),
                    incident.getLatitude(),
                    incident.getLongitude(),
                    incident.getHeadingDegrees(),
                    incident.getPassengersCount(),
                    true
            );

            incident.markDispatchedFallback(fallbackDispatch.dispatchReference(), reason);
            incidentRepository.save(incident);
            return;
        }

        // For MODERATE severity crashes, schedule retry or route to manual review if retries exhausted
        if (currentAttempt + 1 >= maxRetries) {
            log.error("Max retries ({}) exhausted for MODERATE incident {}. Routing to MANUAL_REVIEW_REQUIRED.",
                    maxRetries, incident.getEventId());
            incident.markManualReviewRequired("Max retries exhausted while contacting Insurance Policy API: " + reason);
        } else {
            incident.markRetryScheduled(reason);
        }
        incidentRepository.save(incident);
        throw cause;
    }
}
