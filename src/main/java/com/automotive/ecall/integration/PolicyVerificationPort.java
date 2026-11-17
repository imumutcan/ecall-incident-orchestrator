package com.automotive.ecall.integration;

import java.time.Instant;

/**
 * Outbound port for communicating with Insurance Core Policy & FNOL (First Notice of Loss) systems.
 * Follows Dependency Inversion Principle (DIP): Orchestration depends on this abstraction,
 * allowing real HTTP clients or chaos-enabled simulators to be swapped transparently.
 */
public interface PolicyVerificationPort {

    PolicyFnolResult verifyPolicyAndOpenClaim(
            String eventId,
            String vin,
            Instant crashTimestamp,
            double impactDeltaV,
            double latitude,
            double longitude
    );

    record PolicyFnolResult(
            boolean policyActive,
            String policyNumber,
            String claimReference
    ) {
    }
}
