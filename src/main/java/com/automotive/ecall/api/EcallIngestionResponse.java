package com.automotive.ecall.api;

import com.automotive.ecall.domain.EcallIncident;
import com.automotive.ecall.domain.IncidentStatus;
import com.automotive.ecall.domain.SeverityLevel;

import java.time.Instant;

public record EcallIngestionResponse(
        String eventId,
        String vin,
        SeverityLevel severity,
        IncidentStatus status,
        boolean idempotentReplay,
        Instant receivedAt
) {
    public static EcallIngestionResponse from(EcallIncident incident, boolean idempotentReplay) {
        return new EcallIngestionResponse(
                incident.getEventId(),
                incident.getVin(),
                incident.getSeverity(),
                incident.getStatus(),
                idempotentReplay,
                incident.getCreatedAt()
        );
    }
}
