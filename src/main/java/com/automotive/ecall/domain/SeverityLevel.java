package com.automotive.ecall.domain;

/**
 * Deterministic crash severity classification based on EN 15722 eCall telemetry.
 */
public enum SeverityLevel {
    MODERATE,
    HIGH,
    CRITICAL;

    public boolean requiresImmediateEmergencyDispatch() {
        return this == CRITICAL || this == HIGH;
    }
}
