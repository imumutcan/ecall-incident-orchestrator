package com.automotive.ecall.api;

import com.automotive.ecall.integration.SimulatedInsurancePolicyAdapter;
import com.automotive.ecall.service.TcuFleetSimulatorService;
import com.automotive.ecall.service.TcuFleetSimulatorService.SimulationBurstReport;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/simulation")
public class SimulationAndChaosController {

    private final TcuFleetSimulatorService simulatorService;
    private final SimulatedInsurancePolicyAdapter insurancePolicyAdapter;
    private final CircuitBreaker insurancePolicyCircuitBreaker;

    public SimulationAndChaosController(
            TcuFleetSimulatorService simulatorService,
            SimulatedInsurancePolicyAdapter insurancePolicyAdapter,
            CircuitBreaker insurancePolicyCircuitBreaker) {
        this.simulatorService = simulatorService;
        this.insurancePolicyAdapter = insurancePolicyAdapter;
        this.insurancePolicyCircuitBreaker = insurancePolicyCircuitBreaker;
    }

    @PostMapping("/chaos")
    public ResponseEntity<Map<String, Object>> configureChaos(@Valid @RequestBody ChaosConfigRequest request) {
        insurancePolicyAdapter.configureChaos(request.artificialLatencyMs(), request.failureRatePercent());
        if (request.resetCircuitBreaker()) {
            insurancePolicyCircuitBreaker.reset();
        }
        return ResponseEntity.ok(Map.of(
                "artificialLatencyMs", insurancePolicyAdapter.getArtificialLatencyMs(),
                "failureRatePercent", insurancePolicyAdapter.getFailureRatePercent(),
                "circuitBreakerState", insurancePolicyCircuitBreaker.getState().name()
        ));
    }

    @PostMapping("/burst")
    public ResponseEntity<SimulationBurstReport> triggerBurst(@Valid @RequestBody BurstSimulationRequest request) {
        SimulationBurstReport report = simulatorService.executeBurstSimulation(
                request.uniqueVehicles(),
                request.duplicatePackets(),
                request.concurrencyThreads(),
                request.drainOutboxImmediately()
        );
        return ResponseEntity.ok(report);
    }

    public record ChaosConfigRequest(
            @Min(0) @Max(30000) long artificialLatencyMs,
            @Min(0) @Max(100) int failureRatePercent,
            boolean resetCircuitBreaker
    ) {
    }

    public record BurstSimulationRequest(
            @Min(1) @Max(5000) int uniqueVehicles,
            @Min(0) @Max(5000) int duplicatePackets,
            @Min(1) @Max(64) int concurrencyThreads,
            boolean drainOutboxImmediately
    ) {
    }
}
