package com.automotive.ecall.domain;

/**
 * Lifecycle state machine of an eCall crash incident.
 */
public enum IncidentStatus {
    RECEIVED,
    PROCESSING,
    DISPATCHED_COMPLETE,
    DISPATCHED_FALLBACK,
    RETRY_SCHEDULED,
    MANUAL_REVIEW_REQUIRED
}
