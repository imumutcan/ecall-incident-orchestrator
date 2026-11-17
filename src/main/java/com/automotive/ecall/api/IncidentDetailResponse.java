package com.automotive.ecall.api;

import com.automotive.ecall.domain.EcallIncident;
import com.automotive.ecall.domain.IncidentStatus;
import com.automotive.ecall.domain.SeverityLevel;

import java.time.Instant;

public record IncidentDetailResponse(
        String eventId,
        String vin,
        Instant crashTimestamp,
        double impactDeltaV,
        boolean airbagDeployed,
        boolean rolloverDetected,
        double latitude,
        double longitude,
        int headingDegrees,
        int passengersCount,
        SeverityLevel severity,
        IncidentStatus status,
        String policyNumber,
        String claimReference,
        String dispatchReference,
        String failureReason,
        int retryCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static IncidentDetailResponse from(EcallIncident incident) {
        return new IncidentDetailResponse(
                incident.getEventId(),
                incident.getVin(),
                incident.getCrashTimestamp(),
                incident.getImpactDeltaV(),
                incident.isAirbagDeployed(),
                incident.isRolloverDetected(),
                incident.getLatitude(),
                incident.getLongitude(),
                incident.getHeadingDegrees(),
                incident.getPassengersCount(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getPolicyNumber(),
                incident.getClaimReference(),
                incident.getDispatchReference(),
                incident.getFailureReason(),
                incident.getRetryCount(),
                incident.getCreatedAt(),
                incident.getUpdatedAt()
        );
    }
}
