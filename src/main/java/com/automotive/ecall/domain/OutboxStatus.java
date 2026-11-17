package com.automotive.ecall.domain;

/**
 * Processing state of a transactional outbox event.
 */
public enum OutboxStatus {
    PENDING,
    PROCESSED,
    FAILED_DLQ
}
