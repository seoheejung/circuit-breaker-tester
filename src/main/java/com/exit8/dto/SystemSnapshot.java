package com.exit8.dto;

import java.time.ZonedDateTime;

public record SystemSnapshot(
    ZonedDateTime timestamp,
    // CircuitBreaker
    String circuitBreakerState,
    // Hikari Pool
    int activeConnections,
    int idleConnections,
    int totalConnections,
    int waitingThreads,
    // Hikari timeout counter (누적값)
    double hikariTimeoutCount,
    // 실험용 평균 응답시간
    Long avgResponseTimeMs,
    boolean rateLimitEnabled,
    // redis
    boolean redisCacheEnabled,
    Double cacheHitRatio
) {}
