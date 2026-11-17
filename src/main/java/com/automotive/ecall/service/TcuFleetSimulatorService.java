package com.automotive.ecall.service;

import com.automotive.ecall.api.EcallCrashRequest;
import com.automotive.ecall.api.EcallIngestionResponse;
import com.automotive.ecall.outbox.OutboxRelayScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates a fleet of Connected Vehicles (Mercedes-Benz W1K & Renault VF1 VIN prefixes)
 * transmitting eCall MSD crash packets concurrently during a regional pileup or hailstorm,
 * including realistic cellular modem duplicate retries.
 */
@Service
public class TcuFleetSimulatorService {

    private static final String[] SAMPLE_VIN_PREFIXES = {
            "W1K2050401F", // Mercedes-Benz C-Class
            "W1N2539051F", // Mercedes-Benz GLC
            "VF1RJA00068", // Renault Clio
            "VF1HJD20069"  // Renault Austral
    };

    private final EcallIngestionService ingestionService;
    private final OutboxRelayScheduler outboxRelayScheduler;

    public TcuFleetSimulatorService(
            EcallIngestionService ingestionService,
            OutboxRelayScheduler outboxRelayScheduler) {
        this.ingestionService = ingestionService;
        this.outboxRelayScheduler = outboxRelayScheduler;
    }

    public SimulationBurstReport executeBurstSimulation(
            int uniqueVehicles,
            int duplicatePacketsCount,
            int concurrencyThreads,
            boolean drainOutboxAfterIngestion) {

        List<EcallCrashRequest> packetsToSend = new ArrayList<>(uniqueVehicles + duplicatePacketsCount);
        List<EcallCrashRequest> uniqueRequests = new ArrayList<>(uniqueVehicles);

        for (int i = 0; i < uniqueVehicles; i++) {
            String prefix = SAMPLE_VIN_PREFIXES[i % SAMPLE_VIN_PREFIXES.length];
            String suffix = String.format("%06d", (i + 100001) % 999999);
            String vin = prefix + suffix;
            String eventId = "ECALL-SIM-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();

            boolean critical = (i % 3 != 0); // 67% CRITICAL/HIGH, 33% MODERATE
            double deltaV = critical ? 44.5 + (i % 20) : 18.5 + (i % 5);
            boolean airbag = (i % 2 == 0);
            boolean rollover = (i % 10 == 0);

            EcallCrashRequest req = new EcallCrashRequest(
                    eventId,
                    vin,
                    Instant.now(),
                    deltaV,
                    airbag,
                    rollover,
                    40.9800 + (i * 0.0005),
                    28.9700 + (i * 0.0005),
                    (i * 15) % 360,
                    (i % 4) + 1
            );
            uniqueRequests.add(req);
            packetsToSend.add(req);
        }

        // Append realistic TCU cellular retry duplicates of earlier packets
        for (int d = 0; d < duplicatePacketsCount && !uniqueRequests.isEmpty(); d++) {
            EcallCrashRequest original = uniqueRequests.get(d % uniqueRequests.size());
            packetsToSend.add(original);
        }

        int poolSize = Math.max(1, Math.min(concurrencyThreads, 64));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        List<Double> latenciesMs = Collections.synchronizedList(new ArrayList<>(packetsToSend.size()));
        AtomicInteger newAcceptedCount = new AtomicInteger(0);
        AtomicInteger duplicateReplayCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        long startWallNanos = System.nanoTime();

        List<Callable<Void>> tasks = new ArrayList<>(packetsToSend.size());
        for (EcallCrashRequest packet : packetsToSend) {
            tasks.add(() -> {
                long t0 = System.nanoTime();
                try {
                    EcallIngestionResponse response = ingestionService.ingest(packet);
                    double elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
                    latenciesMs.add(elapsedMs);
                    if (response.idempotentReplay()) {
                        duplicateReplayCount.incrementAndGet();
                    } else {
                        newAcceptedCount.incrementAndGet();
                    }
                } catch (Exception ex) {
                    errorCount.incrementAndGet();
                }
                return null;
            });
        }

        try {
            List<Future<Void>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (Future<Void> f : futures) {
                f.get();
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

        double totalIngestionDurationMs = (System.nanoTime() - startWallNanos) / 1_000_000.0;

        int totalOutboxRelayed = 0;
        if (drainOutboxAfterIngestion) {
            int batch;
            int safetyLoops = 0;
            while ((batch = outboxRelayScheduler.relayPendingBatch()) > 0 && safetyLoops++ < 100) {
                totalOutboxRelayed += batch;
            }
        }

        Collections.sort(latenciesMs);
        double p50 = percentile(latenciesMs, 50);
        double p95 = percentile(latenciesMs, 95);
        double p99 = percentile(latenciesMs, 99);
        double max = latenciesMs.isEmpty() ? 0.0 : latenciesMs.get(latenciesMs.size() - 1);
        double throughputReqPerSec = totalIngestionDurationMs > 0
                ? (packetsToSend.size() * 1000.0) / totalIngestionDurationMs
                : 0.0;

        return new SimulationBurstReport(
                packetsToSend.size(),
                uniqueVehicles,
                duplicatePacketsCount,
                newAcceptedCount.get(),
                duplicateReplayCount.get(),
                errorCount.get(),
                totalOutboxRelayed,
                round2(totalIngestionDurationMs),
                round2(throughputReqPerSec),
                round2(p50),
                round2(p95),
                round2(p99),
                round2(max)
        );
    }

    private double percentile(List<Double> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record SimulationBurstReport(
            int totalPacketsSent,
            int configuredUniqueVehicles,
            int configuredDuplicatePackets,
            int newlyIngestedCount,
            int idempotentReplaysPrevented,
            int ingestionErrorCount,
            int outboxEventsRelayed,
            double totalIngestionDurationMs,
            double throughputReqPerSec,
            double latencyP50Ms,
            double latencyP95Ms,
            double latencyP99Ms,
            double latencyMaxMs
    ) {
    }
}
