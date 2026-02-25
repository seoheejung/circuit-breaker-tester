package com.exit8.observability;

import java.time.Instant;

/**
 * 실시간 요청 관측 이벤트 모델
 */
public record RequestEvent(
        Instant timestamp,
        String traceId,
        String ip,
        String method,
        String path,
        int status,
        String event,
        long durationMs
) {}
