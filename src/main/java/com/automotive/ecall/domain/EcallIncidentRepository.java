package com.automotive.ecall.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EcallIncidentRepository extends JpaRepository<EcallIncident, Long> {

    Optional<EcallIncident> findByEventId(String eventId);

    List<EcallIncident> findByVinOrderByCrashTimestampDesc(String vin);

    long countByStatus(IncidentStatus status);

    long countBySeverity(SeverityLevel severity);

    @Query("SELECT COUNT(i) FROM EcallIncident i WHERE i.status = :status AND i.severity = :severity")
    long countByStatusAndSeverity(
            @Param("status") IncidentStatus status,
            @Param("severity") SeverityLevel severity);
}
