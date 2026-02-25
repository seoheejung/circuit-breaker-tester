package com.exit8.config.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 관측 메트릭 정의
 * - RateLimit 관련 Counter를 명시적으로 등록
 * - Prometheus / Grafana 시계열 분석용
 */
@Configuration
public class MetricsConfig {

    /**
     * 모든 메트릭에 공통 tag 부여
     * Grafana에서 서비스 단위 필터링 가능
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags("service", "service-a-backend");
    }

    /**
     * Rate Limit 차단 누적 카운터
     * label을 두지 않아 cardinality 증가 방지
     */
    @Bean
    public Counter rateLimitBlockedCounter(MeterRegistry registry) {
        return Counter.builder("rate_limit_blocked_total")
                .description("Total blocked requests by rate limit")
                .register(registry);
    }

    /**
     * Rate Limit 통과 누적 카운터
     */
    @Bean
    public Counter rateLimitAllowedCounter(MeterRegistry registry) {
        return Counter.builder("rate_limit_allowed_total")
                .description("Total allowed requests by rate limit")
                .register(registry);
    }

    /**
     * Redis Cache Hit 누적 카운터
     */
    @Bean
    public Counter cacheHit(MeterRegistry registry) {
        return Counter.builder("cache_hit_total")
                .description("Redis cache hit count")
                .register(registry);
    }

    /**
     * Redis Cache Miss 누적 카운터
     * (Redis 장애 fallback 포함)
     */
    @Bean
    public Counter cacheMiss(MeterRegistry registry) {
        return Counter.builder("cache_miss_total")
                .description("Redis cache miss count")
                .register(registry);
    }

}
