package com.exit8.config.filter;

import com.exit8.filter.ClientIpResolver;
import com.exit8.filter.RateLimitFilter;
import com.exit8.filter.TraceIdFilter;
import com.exit8.observability.RequestEventBuffer;
import com.exit8.service.SystemHealthService;
import io.micrometer.core.instrument.Counter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filter 실행 순서를 명시적으로 고정하기 위한 작업
 *
 * 1. TraceIdFilter   : 요청 진입 시 trace_id 생성
 * 2. RateLimitFilter : trace_id 생성 이후 1차 방어 수행
 *
 * 로직은 Filter에 두고, 이 클래스는 "순서"만 책임
 */
@Configuration
public class FilterOrderConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        // 파라미터에서 TraceIdFilter filter를 제거하고 메서드 내부에서 직접 생성
        TraceIdFilter filter = new TraceIdFilter();

        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);

        // trace_id 먼저 생성
        registration.setOrder(1);

        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            ClientIpResolver clientIpResolver,
            SystemHealthService systemHealthService,
            RequestEventBuffer requestEventBuffer,
            Counter rateLimitBlockedCounter,
            Counter rateLimitAllowedCounter
    ) {

        RateLimitFilter filter =
                new RateLimitFilter(
                        clientIpResolver,
                        systemHealthService,
                        requestEventBuffer,
                        rateLimitBlockedCounter,
                        rateLimitAllowedCounter
                );

        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>();

        registration.setFilter(filter);

        // trace_id 생성 이후 실행
        registration.setOrder(2);

        return registration;
    }
}