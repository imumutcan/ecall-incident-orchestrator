package com.automotive.ecall.service;

import com.automotive.ecall.domain.SeverityLevel;
import org.springframework.stereotype.Component;

/**
 * Evaluates EN 15722 eCall telemetry parameters to classify crash urgency.
 * Single Responsibility: Pure domain triage logic without infrastructure dependencies.
 */
@Component
public class SeverityCalculator {

    private static final double CRITICAL_DELTA_V_THRESHOLD = 40.0;
    private static final double HIGH_DELTA_V_THRESHOLD = 25.0;

    public SeverityLevel calculate(double impactDeltaV, boolean airbagDeployed, boolean rolloverDetected) {
        if (airbagDeployed || rolloverDetected || impactDeltaV >= CRITICAL_DELTA_V_THRESHOLD) {
            return SeverityLevel.CRITICAL;
        }
        if (impactDeltaV >= HIGH_DELTA_V_THRESHOLD) {
            return SeverityLevel.HIGH;
        }
        return SeverityLevel.MODERATE;
    }
}
