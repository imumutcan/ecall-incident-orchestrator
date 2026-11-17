package com.automotive.ecall.api;

import com.automotive.ecall.service.EcallIngestionService;
import com.automotive.ecall.service.OperationalMetricsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ecall")
public class EcallIncidentController {

    private final EcallIngestionService ingestionService;
    private final OperationalMetricsService metricsService;

    public EcallIncidentController(
            EcallIngestionService ingestionService,
            OperationalMetricsService metricsService) {
        this.ingestionService = ingestionService;
        this.metricsService = metricsService;
    }

    /**
     * Receives an EN 15722 eCall Minimum Set of Data (MSD) crash packet from a vehicle TCU.
     * Returns HTTP 202 Accepted for newly ingested events, or HTTP 200 OK if the packet
     * is an idempotent replay of an already received crash event.
     */
    @PostMapping("/events")
    public ResponseEntity<EcallIngestionResponse> ingestCrashEvent(@Valid @RequestBody EcallCrashRequest request) {
        EcallIngestionResponse response = ingestionService.ingest(request);
        HttpStatus status = response.idempotentReplay() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/incidents/{eventId}")
    public ResponseEntity<IncidentDetailResponse> getIncident(@PathVariable String eventId) {
        return ResponseEntity.ok(ingestionService.getByEventId(eventId));
    }

    @GetMapping("/stats")
    public ResponseEntity<OperationalStatsResponse> getStats() {
        return ResponseEntity.ok(metricsService.collectStats());
    }
}
