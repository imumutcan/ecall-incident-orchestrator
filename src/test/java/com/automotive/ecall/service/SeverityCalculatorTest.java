package com.automotive.ecall.service;

import com.automotive.ecall.domain.SeverityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeverityCalculatorTest {

    private final SeverityCalculator calculator = new SeverityCalculator();

    @Test
    @DisplayName("Airbag deployment always classifies crash as CRITICAL regardless of deltaV")
    void shouldClassifyAsCriticalWhenAirbagDeployed() {
        SeverityLevel level = calculator.calculate(18.0, true, false);
        assertThat(level).isEqualTo(SeverityLevel.CRITICAL);
        assertThat(level.requiresImmediateEmergencyDispatch()).isTrue();
    }

    @Test
    @DisplayName("Rollover detection always classifies crash as CRITICAL")
    void shouldClassifyAsCriticalWhenRolloverDetected() {
        SeverityLevel level = calculator.calculate(20.0, false, true);
        assertThat(level).isEqualTo(SeverityLevel.CRITICAL);
    }

    @Test
    @DisplayName("Impact deltaV >= 40.0 km/h classifies crash as CRITICAL")
    void shouldClassifyAsCriticalOnHighDeltaVBoundary() {
        assertThat(calculator.calculate(40.0, false, false)).isEqualTo(SeverityLevel.CRITICAL);
        assertThat(calculator.calculate(65.5, false, false)).isEqualTo(SeverityLevel.CRITICAL);
    }

    @Test
    @DisplayName("Impact deltaV between 25.0 and 39.9 km/h without airbag/rollover classifies as HIGH")
    void shouldClassifyAsHighForMediumImpact() {
        assertThat(calculator.calculate(25.0, false, false)).isEqualTo(SeverityLevel.HIGH);
        assertThat(calculator.calculate(39.9, false, false)).isEqualTo(SeverityLevel.HIGH);
        assertThat(SeverityLevel.HIGH.requiresImmediateEmergencyDispatch()).isTrue();
    }

    @Test
    @DisplayName("Impact deltaV below 25.0 km/h without airbag/rollover classifies as MODERATE")
    void shouldClassifyAsModerateForLowImpact() {
        SeverityLevel level = calculator.calculate(15.0, false, false);
        assertThat(level).isEqualTo(SeverityLevel.MODERATE);
        assertThat(level.requiresImmediateEmergencyDispatch()).isFalse();
    }
}
