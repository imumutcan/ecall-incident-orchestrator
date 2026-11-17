package com.automotive.ecall.integration;

import com.automotive.ecall.domain.SeverityLevel;

/**
 * Outbound port for Roadside Assistance & Emergency Tow Dispatch systems.
 */
public interface RoadsideDispatchPort {

    DispatchResult dispatchAssistance(
            String eventId,
            String vin,
            SeverityLevel severity,
            double latitude,
            double longitude,
            int headingDegrees,
            int passengersCount,
            boolean emergencyFallbackMode
    );

    record DispatchResult(
            String dispatchReference,
            String assignedUnitType,
            int estimatedArrivalMinutes
    ) {
    }
}
