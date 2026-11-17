package com.automotive.ecall.service;

import com.automotive.ecall.api.EcallCrashRequest;
import com.automotive.ecall.api.EcallIngestionResponse;
import com.automotive.ecall.domain.EcallIncidentRepository;
import com.automotive.ecall.domain.OutboxEvent;
import com.automotive.ecall.domain.OutboxEventRepository;
import com.automotive.ecall.domain.OutboxStatus;
import com.automotive.ecall.domain.SeverityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ecall.outbox.scheduler-enabled=false"
})
class EcallIngestionConcurrencyTest {

    @Autowired
    private EcallIngestionService ingestionService;

    @Autowired
    private EcallIncidentRepository incidentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        incidentRepository.deleteAll();
    }

    @Test
    @DisplayName("Sprint 1 Exit Metric: 20 concurrent threads sending the exact same eventId must result in 1 DB row, 1 Outbox event, and 19 idempotent replays")
    void shouldHandleConcurrentDuplicatePacketsWithZeroDuplicateRows() throws Exception {
        String sharedEventId = "ECALL-CONCURRENT-BURST-001";
        EcallCrashRequest crashPacket = new EcallCrashRequest(
                sharedEventId,
                "W1K2050401F123456", // Mercedes-Benz W205 VIN
                Instant.now(),
                52.4,
                true,
                false,
                41.0082,
                28.9784,
                145,
                2
        );

        int concurrentThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch readyLatch = new CountDownLatch(concurrentThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<EcallIngestionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentThreads; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                return ingestionService.ingest(crashPacket);
            });
        }

        List<Future<EcallIngestionResponse>> futures = new ArrayList<>();
        for (Callable<EcallIngestionResponse> task : tasks) {
            futures.add(executor.submit(task));
        }

        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown(); // Release all 20 threads simultaneously

        List<EcallIngestionResponse> responses = new ArrayList<>();
        for (Future<EcallIngestionResponse> future : futures) {
            responses.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        long firstTimeIngestedCount = responses.stream()
                .filter(r -> !r.idempotentReplay())
                .count();
        long idempotentReplayCount = responses.stream()
                .filter(EcallIngestionResponse::idempotentReplay)
                .count();

        assertThat(firstTimeIngestedCount).isEqualTo(1L);
        assertThat(idempotentReplayCount).isEqualTo(19L);

        assertThat(incidentRepository.count()).isEqualTo(1L);
        assertThat(outboxEventRepository.count()).isEqualTo(1L);

        Optional<OutboxEvent> outbox = outboxEventRepository.findByAggregateId(sharedEventId);
        assertThat(outbox).isPresent();
        assertThat(outbox.get().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(responses.get(0).severity()).isEqualTo(SeverityLevel.CRITICAL);
    }
}
