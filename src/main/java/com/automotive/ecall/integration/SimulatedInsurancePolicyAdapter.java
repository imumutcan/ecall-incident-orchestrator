package com.automotive.ecall.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulates an external Insurance Core Policy & FNOL (First Notice of Loss) system.
 * Supports runtime fault injection (artificial latency and failure percentage) so that
 * Circuit Breaker, Retry, and Emergency Fallback behavior can be deterministically verified.
 */
@Component
public class SimulatedInsurancePolicyAdapter implements PolicyVerificationPort {

    private static final Logger log = LoggerFactory.getLogger(SimulatedInsurancePolicyAdapter.class);

    private final AtomicLong artificialLatencyMs = new AtomicLong(0);
    private final AtomicInteger failureRatePercent = new AtomicInteger(0);
    private final Set<String> uninsurableVins = ConcurrentHashMap.newKeySet();
    private final Map<String, PolicyFnolResult> openedClaimsByEventId = new ConcurrentHashMap<>();

    @Override
    public PolicyFnolResult verifyPolicyAndOpenClaim(
            String eventId,
            String vin,
            Instant crashTimestamp,
            double impactDeltaV,
            double latitude,
            double longitude) {

        long delay = artificialLatencyMs.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExternalServiceException("Interrupted while waiting on Insurance Policy API", e);
            }
        }

        int failRate = failureRatePercent.get();
        if (failRate > 0) {
            int roll = (failRate >= 100) ? 0 : ThreadLocalRandom.current().nextInt(100);
            if (roll < failRate) {
                log.warn("Simulated HTTP 503 from Insurance Policy Service for eventId={}, vin={}", eventId, vin);
                throw new ExternalServiceException("HTTP 503 Service Unavailable: Legacy Insurance Policy & FNOL Core timed out");
            }
        }

        // Idempotent on partner side if called twice for same eventId
        return openedClaimsByEventId.computeIfAbsent(eventId, id -> {
            if (uninsurableVins.contains(vin) || vin.endsWith("00000")) {
                return new PolicyFnolResult(false, null, null);
            }
            String policyNo = "POL-TR-" + vin.substring(Math.max(0, vin.length() - 6));
            String claimRef = "FNOL-" + Math.abs(id.hashCode() % 900000 + 100000);
            return new PolicyFnolResult(true, policyNo, claimRef);
        });
    }

    public void configureChaos(long latencyMs, int failurePercent) {
        this.artificialLatencyMs.set(Math.max(0, latencyMs));
        this.failureRatePercent.set(Math.max(0, Math.min(100, failurePercent)));
        log.info("Configured Insurance Policy Adapter chaos: latencyMs={}, failurePercent={}%",
                this.artificialLatencyMs.get(), this.failureRatePercent.get());
    }

    public void resetChaos() {
        this.artificialLatencyMs.set(0);
        this.failureRatePercent.set(0);
        this.uninsurableVins.clear();
    }

    public void markVinUninsured(String vin) {
        this.uninsurableVins.add(vin);
    }

    public long getArtificialLatencyMs() {
        return artificialLatencyMs.get();
    }

    public int getFailureRatePercent() {
        return failureRatePercent.get();
    }
}
