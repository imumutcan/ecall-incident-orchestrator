package com.automotive.ecall.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(
    name = "ecall_incidents",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_ecall_incidents_event_id", columnNames = "event_id")
    }
)
public class EcallIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64, updatable = false)
    private String eventId;

    @Column(name = "vin", nullable = false, length = 17, updatable = false)
    private String vin;

    @Column(name = "crash_timestamp", nullable = false, updatable = false)
    private Instant crashTimestamp;

    @Column(name = "impact_delta_v", nullable = false, updatable = false)
    private double impactDeltaV;

    @Column(name = "airbag_deployed", nullable = false, updatable = false)
    private boolean airbagDeployed;

    @Column(name = "rollover_detected", nullable = false, updatable = false)
    private boolean rolloverDetected;

    @Column(name = "latitude", nullable = false, updatable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false, updatable = false)
    private double longitude;

    @Column(name = "heading_degrees", nullable = false, updatable = false)
    private int headingDegrees;

    @Column(name = "passengers_count", nullable = false, updatable = false)
    private int passengersCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private SeverityLevel severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IncidentStatus status;

    @Column(name = "policy_number", length = 40)
    private String policyNumber;

    @Column(name = "claim_reference", length = 40)
    private String claimReference;

    @Column(name = "dispatch_reference", length = 40)
    private String dispatchReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EcallIncident() {
        // JPA required constructor
    }

    public EcallIncident(
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
            SeverityLevel severity) {
        Instant now = Instant.now();
        this.eventId = eventId;
        this.vin = vin;
        this.crashTimestamp = crashTimestamp;
        this.impactDeltaV = impactDeltaV;
        this.airbagDeployed = airbagDeployed;
        this.rolloverDetected = rolloverDetected;
        this.latitude = latitude;
        this.longitude = longitude;
        this.headingDegrees = headingDegrees;
        this.passengersCount = passengersCount;
        this.severity = severity;
        this.status = IncidentStatus.RECEIVED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markProcessing() {
        this.status = IncidentStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markDispatchedComplete(String policyNumber, String claimReference, String dispatchReference) {
        this.policyNumber = policyNumber;
        this.claimReference = claimReference;
        this.dispatchReference = dispatchReference;
        this.failureReason = null;
        this.status = IncidentStatus.DISPATCHED_COMPLETE;
        this.updatedAt = Instant.now();
    }

    public void markDispatchedFallback(String dispatchReference, String reason) {
        this.dispatchReference = dispatchReference;
        this.failureReason = truncateReason(reason);
        this.status = IncidentStatus.DISPATCHED_FALLBACK;
        this.updatedAt = Instant.now();
    }

    public void markRetryScheduled(String reason) {
        this.retryCount++;
        this.failureReason = truncateReason(reason);
        this.status = IncidentStatus.RETRY_SCHEDULED;
        this.updatedAt = Instant.now();
    }

    public void markManualReviewRequired(String reason) {
        this.failureReason = truncateReason(reason);
        this.status = IncidentStatus.MANUAL_REVIEW_REQUIRED;
        this.updatedAt = Instant.now();
    }

    private String truncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > 490 ? reason.substring(0, 490) : reason;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getVin() {
        return vin;
    }

    public Instant getCrashTimestamp() {
        return crashTimestamp;
    }

    public double getImpactDeltaV() {
        return impactDeltaV;
    }

    public boolean isAirbagDeployed() {
        return airbagDeployed;
    }

    public boolean isRolloverDetected() {
        return rolloverDetected;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public int getHeadingDegrees() {
        return headingDegrees;
    }

    public int getPassengersCount() {
        return passengersCount;
    }

    public SeverityLevel getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getClaimReference() {
        return claimReference;
    }

    public String getDispatchReference() {
        return dispatchReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
