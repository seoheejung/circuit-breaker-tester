package com.exit8.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SystemHealthStatus {

    private String status;          // UP / DEGRADED / DOWN
    private String circuitBreaker;  // CLOSED / OPEN / HALF_OPEN
    private Long avgResponseTimeMs;

    private String reason;          // null 가능
}