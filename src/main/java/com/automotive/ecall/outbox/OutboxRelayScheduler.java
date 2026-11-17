package com.automotive.ecall.outbox;

import com.automotive.ecall.domain.OutboxEvent;
import com.automotive.ecall.domain.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Component
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxEventRepository outboxEventRepository;
    private final IncidentEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxRetries;
    private final boolean schedulerEnabled;

    public OutboxRelayScheduler(
            OutboxEventRepository outboxEventRepository,
            IncidentEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate,
            @Value("${ecall.outbox.batch-size:50}") int batchSize,
            @Value("${ecall.outbox.max-retries:4}") int maxRetries,
            @Value("${ecall.outbox.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.schedulerEnabled = schedulerEnabled;
    }

    @Scheduled(fixedDelayString = "${ecall.outbox.polling-interval-ms:150}")
    public void scheduledRelay() {
        if (!schedulerEnabled) {
            return;
        }
        relayPendingBatch();
    }

    /**
     * Polls up to batchSize pending outbox records using FOR UPDATE SKIP LOCKED
     * and dispatches each event. Returns the number of events processed in this batch.
     */
    public int relayPendingBatch() {
        Integer count = transactionTemplate.execute(status -> {
            List<OutboxEvent> pendingBatch = outboxEventRepository.findPendingBatchForUpdateSkipLocked(
                    Instant.now(),
                    batchSize
            );

            for (OutboxEvent event : pendingBatch) {
                try {
                    eventPublisher.publish(
                            event.getAggregateId(),
                            event.getPayload(),
                            event.getRetryCount(),
                            maxRetries
                    );
                    event.markProcessed();
                } catch (Exception ex) {
                    log.warn("Outbox event processing failed for aggregateId={}, retryCount={}: {}",
                            event.getAggregateId(), event.getRetryCount(), ex.getMessage());
                    event.recordFailedAttempt(maxRetries, 100L);
                }
                outboxEventRepository.save(event);
            }
            return pendingBatch.size();
        });
        return count != null ? count : 0;
    }
}
