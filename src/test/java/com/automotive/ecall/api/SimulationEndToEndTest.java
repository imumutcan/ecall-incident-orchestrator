package com.automotive.ecall.api;

import com.automotive.ecall.domain.EcallIncidentRepository;
import com.automotive.ecall.domain.OutboxEventRepository;
import com.automotive.ecall.integration.SimulatedInsurancePolicyAdapter;
import com.automotive.ecall.service.TcuFleetSimulatorService.SimulationBurstReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ecall.outbox.scheduler-enabled=false"
})
@AutoConfigureMockMvc
class SimulationEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EcallIncidentRepository incidentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SimulatedInsurancePolicyAdapter insurancePolicyAdapter;

    @Autowired
    private CircuitBreaker insurancePolicyCircuitBreaker;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        incidentRepository.deleteAll();
        insurancePolicyAdapter.resetChaos();
        insurancePolicyCircuitBreaker.reset();
    }

    @Test
    @DisplayName("Sprint 3 Exit Metric 1: 100-packet burst (75 unique vehicles + 25 duplicates) ingests 75 incidents and blocks 25 duplicates with p95 < 60ms")
    void shouldProcess100PacketFleetBurstWithExactIdempotencyAndLowLatency() throws Exception {
        String requestBody = """
                {
                  "uniqueVehicles": 75,
                  "duplicatePackets": 25,
                  "concurrencyThreads": 16,
                  "drainOutboxImmediately": true
                }
                """;

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/simulation/burst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        SimulationBurstReport report = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(),
                SimulationBurstReport.class
        );

        assertThat(report.totalPacketsSent()).isEqualTo(100);
        assertThat(report.newlyIngestedCount()).isEqualTo(75);
        assertThat(report.idempotentReplaysPrevented()).isEqualTo(25);
        assertThat(report.ingestionErrorCount()).isZero();
        assertThat(report.outboxEventsRelayed()).isEqualTo(75);
        assertThat(report.latencyP95Ms()).isLessThan(60.0);

        mockMvc.perform(get("/api/v1/ecall/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncidents").value(75))
                .andExpect(jsonPath("$.outboxProcessedCount").value(75))
                .andExpect(jsonPath("$.outboxPendingCount").value(0))
                .andExpect(jsonPath("$.incidentsByStatus.DISPATCHED_COMPLETE").value(75));
    }

    @Test
    @DisplayName("Sprint 3 Exit Metric 2: Invalid VIN and deltaV < 15.0 km/h are rejected with RFC 7807 ProblemDetail HTTP 400")
    void shouldRejectInvalidTelemetryPayloadWithStructuredProblemDetail() throws Exception {
        String invalidPayload = """
                {
                  "eventId": "ECALL-INVALID-001",
                  "vin": "SHORTVIN123",
                  "crashTimestamp": "2026-11-17T10:15:30Z",
                  "impactDeltaV": 8.5,
                  "airbagDeployed": false,
                  "rolloverDetected": false,
                  "latitude": 41.0,
                  "longitude": 29.0,
                  "headingDegrees": 180,
                  "passengersCount": 1
                }
                """;

        mockMvc.perform(post("/api/v1/ecall/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Telemetry Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors.vin").exists())
                .andExpect(jsonPath("$.fieldErrors.impactDeltaV").exists());
    }
}
