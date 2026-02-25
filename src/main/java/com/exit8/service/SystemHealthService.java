package com.exit8.service;

import com.exit8.config.constants.CircuitNames;
import com.exit8.dto.SystemHealthStatus;
import com.exit8.dto.SystemSnapshot;
import com.exit8.dto.ToggleResponse;
import com.exit8.exception.ApiException;
import com.exit8.observability.CacheMetrics;
import com.exit8.observability.RequestEvent;
import com.exit8.observability.RequestEventBuffer;
import com.exit8.state.RuntimeFeatureState;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

import javax.sql.DataSource;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SystemHealthService {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final DataSource dataSource;
    private final MeterRegistry meterRegistry;
    private final RequestEventBuffer requestEventBuffer;
    private final RuntimeFeatureState runtimeFeatureState;
    private final CacheMetrics cacheMetrics;

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 기존 Health 판단 로직 (운영 상태 판정용)
     * 이 메서드는 절대 Raw 계측 용도로 사용하지 않는다.
     */
    public SystemHealthStatus getCurrentStatus() {

        CircuitBreaker circuitBreaker =
                circuitBreakerRegistry.find(CircuitNames.TEST_CIRCUIT)
                        .orElseThrow(() -> new ApiException(
                                "CIRCUIT_NOT_FOUND",
                                "circuit breaker not registered: " + CircuitNames.TEST_CIRCUIT,
                                HttpStatus.INTERNAL_SERVER_ERROR
                        ));

        String cbState = circuitBreaker.getState().name();

        String status = "UP";
        String reason = null;

        /* CircuitBreaker 상태가 최우선 */
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            return new SystemHealthStatus(
                    "DOWN",
                    cbState,
                    null,
                    "CIRCUIT_BREAKER_OPEN"
            );
        }

        // load.scenario 전체 타입(cpu, db_read, db_write 등)의 평균 응답 시간 계산
        Long avgResponseTimeMs = null;

        var timers = meterRegistry
                .find("load.scenario")
                .timers();

        if (!timers.isEmpty()) {
            double totalTime = 0;
            long totalCount = 0;

            // 모든 Timer의 총 수행 시간과 호출 횟수 합산
            for (Timer t : timers) {
                totalTime += t.totalTime(TimeUnit.MILLISECONDS);
                totalCount += t.count();
            }

            // 전체 평균 응답 시간(ms) 계산
            if (totalCount > 0) {
                avgResponseTimeMs = Math.round(totalTime / totalCount);
            }
        }


        if (circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN) {
            return new SystemHealthStatus(
                    "DEGRADED",
                    cbState,
                    avgResponseTimeMs,
                    "CIRCUIT_BREAKER_HALF_OPEN"
            );
        }

        /* DB 상태는 CLOSED 일 때만 평가 */
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();

            if (pool == null) {
                status = "DEGRADED";
                reason = "DB_POOL_MBEAN_UNAVAILABLE";
            } else {
                int active = pool.getActiveConnections();
                int idle = pool.getIdleConnections();
                int waiting = pool.getThreadsAwaitingConnection();

                if (waiting > 0) {
                    status = "DEGRADED";
                    reason = "DB_CONNECTION_POOL_WAITING";
                }

                if (idle == 0 && waiting > 0) {
                    status = "DEGRADED";
                    reason = "DB_CONNECTION_POOL_EXHAUSTED";
                }
            }
        }

        return new SystemHealthStatus(
                status,
                cbState,
                avgResponseTimeMs,
                reason
        );
    }

    /**
     * 프론트 상단 상태 표시의 단일 데이터 소스
     */
    public SystemSnapshot getSnapshot() {

        // CircuitBreaker 상태
        CircuitBreaker cb =
                circuitBreakerRegistry.circuitBreaker(CircuitNames.TEST_CIRCUIT);

        String cbState = cb.getState().name();

        // Hikari Pool Raw 값
        int active = 0;
        int idle = 0;
        int total = 0;
        int waiting = 0;

        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();

            if (pool != null) {
                active = pool.getActiveConnections();
                idle = pool.getIdleConnections();
                total = pool.getTotalConnections();
                waiting = pool.getThreadsAwaitingConnection();
            }
        }

        // Hikari timeout 누적 카운터
        double timeoutCount = 0;

        Counter timeoutCounter =
                meterRegistry.find("hikaricp.connections.timeout").counter();

        if (timeoutCounter != null) {
            timeoutCount = timeoutCounter.count();
        }

        // Redis Cache Hit Ratio 계산
        double hitCount = cacheMetrics.getHitCount();
        double missCount = cacheMetrics.getMissCount();

        Double cacheHitRatio = null;

        double totalCache = hitCount + missCount;

        if (totalCache > 0) {
            cacheHitRatio = hitCount / totalCache;
        }

        // 전체 HTTP 평균 응답 시간
        Collection<Timer> timers =
                meterRegistry.find("http.server.requests").timers();

        double totalTime = 0;
        long totalCount = 0;

        for (Timer t : timers) {
            totalTime += t.totalTime(TimeUnit.MILLISECONDS);
            totalCount += t.count();
        }

        Long avgResponseTimeMs =
                totalCount > 0 ? Math.round(totalTime / totalCount) : null;

        // Snapshot은 판단하지 않고 Raw 값만 반환
        return new SystemSnapshot(
                ZonedDateTime.now(ZONE), // timestamp (DTO 타입이 ZonedDateTime임)
                cbState,
                active,
                idle,
                total,
                waiting,
                timeoutCount,
                avgResponseTimeMs,
                runtimeFeatureState.isRateLimitEnabled(),
                runtimeFeatureState.isRedisCacheEnabled(),
                cacheHitRatio
        );
    }

    /**
     * Rate Limit 상태를 ON ↔ OFF로 전환
     */
    public ToggleResponse toggleRateLimit() {
        boolean prev = runtimeFeatureState.isRateLimitEnabled();
        boolean next = runtimeFeatureState.toggleRateLimit();

        // 상태 변화를 문자열로 생성 (예: "OFF -> ON" 또는 "ON -> OFF")
        String fromStatus = prev ? "ON" : "OFF";
        String toStatus = next ? "ON" : "OFF";
        String statusMessage = String.format("%s -> %s", fromStatus, toStatus);

        return new ToggleResponse(next, statusMessage);
    }

    /**
     * 현재 Rate Limit 활성화 여부 조회
     */
    public boolean isRateLimitEnabled() {
        return runtimeFeatureState.isRateLimitEnabled();
    }

    /**
     * Redis 상태를 ON ↔ OFF로 전환
     */
    public ToggleResponse toggleRedisCache() {
        boolean prev = runtimeFeatureState.isRedisCacheEnabled();
        boolean next = runtimeFeatureState.toggleRedisCache();

        String fromStatus = prev ? "ON" : "OFF";
        String toStatus = next ? "ON" : "OFF";
        String statusMessage = String.format("%s -> %s", fromStatus, toStatus);

        return new ToggleResponse(next, statusMessage);
    }

    /**
     * 현재 Redis 활성화 여부 조회
     */
    public boolean isRedisCacheEnabled() {
        return runtimeFeatureState.isRedisCacheEnabled();
    }

    /**
     * 최근 요청 이벤트 조회
     */
    public List<RequestEvent> getRecentRequests(int limit) {
        return requestEventBuffer.getRecent(limit);
    }

}