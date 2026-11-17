package com.automotive.ecall.integration;

import com.automotive.ecall.domain.SeverityLevel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates the Roadside Assistance & Emergency Tow Dispatch partner service.
 * Ensures idempotent dispatch assignment per eventId so a retried orchestration never
 * sends two tow trucks to the same vehicle.
 */
@Component
public class SimulatedRoadsideDispatchAdapter implements RoadsideDispatchPort {

    private final Map<String, DispatchResult> activeDispatchesByEventId = new ConcurrentHashMap<>();

    @Override
    public DispatchResult dispatchAssistance(
            String eventId,
            String vin,
            SeverityLevel severity,
            double latitude,
            double longitude,
            int headingDegrees,
            int passengersCount,
            boolean emergencyFallbackMode) {

        return activeDispatchesByEventId.computeIfAbsent(eventId, id -> {
            String prefix = switch (severity) {
                case CRITICAL -> emergencyFallbackMode ? "EMERG-FB-TOW-" : "EMERG-TOW-";
                case HIGH -> emergencyFallbackMode ? "PRIO-FB-TOW-" : "PRIO-TOW-";
                case MODERATE -> "STD-TOW-";
            };
            String dispatchRef = prefix + Math.abs(id.hashCode() % 900000 + 100000);
            String unitType = (severity == SeverityLevel.CRITICAL)
                    ? "EMERGENCY_RESPONSE_AND_HEAVY_TOW"
                    : "ROADSIDE_RECOVERY_TRUCK";
            int etaMinutes = (severity == SeverityLevel.CRITICAL) ? 12 : 25;
            return new DispatchResult(dispatchRef, unitType, etaMinutes);
        });
    }

    public int getActiveDispatchCount() {
        return activeDispatchesByEventId.size();
    }
}
