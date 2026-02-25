package com.exit8.observability;

import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CacheMetrics {

    // Redis Cache Hit 횟수 누적 카운터
    private final Counter cacheHit;

    // Redis Cache Miss 횟수 누적 카운터
    private final Counter cacheMiss;

    // 캐시 적중 시 호출
    public void incrementHit() {
        cacheHit.increment();
    }

    // 캐시 미적중 시 호출 (Redis 장애 fallback 포함)
    public void incrementMiss() {
        cacheMiss.increment();
    }

    public double getHitCount() {
        return cacheHit.count();
    }

    public double getMissCount() {
        return cacheMiss.count();
    }
}