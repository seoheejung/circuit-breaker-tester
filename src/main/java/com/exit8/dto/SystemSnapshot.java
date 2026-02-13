package com.exit8.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Getter
@AllArgsConstructor
public class SystemSnapshot {
    private ZonedDateTime timestamp;

    // CircuitBreaker
    private String circuitBreakerState;

    // Hikari Pool
    private int activeConnections;
    private int idleConnections;
    private int totalConnections;
    private int waitingThreads;

    // Hikari timeout counter (누적값)
    private double hikariTimeoutCount;

    // 실험용 평균 응답시간
    private Long avgResponseTimeMs;
}
