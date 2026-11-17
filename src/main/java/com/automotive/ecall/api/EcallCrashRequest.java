package com.automotive.ecall.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * EN 15722 eCall Minimum Set of Data (MSD) crash telemetry payload transmitted by Vehicle TCU.
 */
public record EcallCrashRequest(
        @NotBlank(message = "eventId is required")
        @Size(max = 64, message = "eventId must not exceed 64 characters")
        String eventId,

        @NotBlank(message = "vin is required")
        @Pattern(regexp = "^[A-HJ-NPR-Z0-9]{17}$", message = "vin must be a valid 17-character ISO 3779 VIN")
        String vin,

        @NotNull(message = "crashTimestamp is required")
        Instant crashTimestamp,

        @DecimalMin(value = "15.0", message = "impactDeltaV must be at least 15.0 km/h to trigger eCall")
        @DecimalMax(value = "350.0", message = "impactDeltaV exceeds sensor physical bounds")
        double impactDeltaV,

        boolean airbagDeployed,

        boolean rolloverDetected,

        @DecimalMin(value = "-90.0", message = "latitude must be >= -90.0")
        @DecimalMax(value = "90.0", message = "latitude must be <= 90.0")
        double latitude,

        @DecimalMin(value = "-180.0", message = "longitude must be >= -180.0")
        @DecimalMax(value = "180.0", message = "longitude must be <= 180.0")
        double longitude,

        @Min(value = 0, message = "headingDegrees must be between 0 and 359")
        @Max(value = 359, message = "headingDegrees must be between 0 and 359")
        int headingDegrees,

        @Min(value = 1, message = "passengersCount must be at least 1")
        @Max(value = 9, message = "passengersCount must not exceed 9")
        int passengersCount
) {
}
