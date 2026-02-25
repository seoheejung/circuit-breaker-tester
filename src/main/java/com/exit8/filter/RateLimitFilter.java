package com.exit8.filter;

import com.exit8.logging.LogEvent;
import com.exit8.logging.TraceContext;
import com.exit8.observability.RequestEvent;
import com.exit8.observability.RequestEventBuffer;
import com.exit8.service.SystemHealthService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * IP 기반 Rate Limit Filter
     *
     * - TraceIdFilter 이후 실행 (trace_id 존재 보장)
     * - /api/load 경로에만 적용
     * - Bucket4j Token Bucket 방식 사용
     * - CircuitBreaker 이전 1차 방어 레이어
     * - 런타임 ON/OFF 가능
     * - 차단/허용 이벤트를 메트릭 + RequestEventBuffer 기록
     */

    private final ClientIpResolver clientIpResolver;
    private final SystemHealthService systemHealthService;
    private final RequestEventBuffer requestEventBuffer;
    private final Counter rateLimitBlockedCounter;
    private final Counter rateLimitAllowedCounter;

    /**
     * IP별 Bucket 저장소
     * - 10분간 미사용 시 제거
     * - 최대 10,000 IP 유지
     */
    private final Cache<String, Bucket> bucketStore =
            Caffeine.newBuilder()
                    .expireAfterAccess(10, TimeUnit.MINUTES)
                    .maximumSize(10_000)
                    .build();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // TraceIdFilter에서 이미 생성된 trace_id 조회
        String traceId = MDC.get(TraceContext.TRACE_ID_KEY);

        // 테스트용 API에만 적용
        if (!request.getRequestURI().startsWith("/api/load")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 실제 클라이언트 IP 추출 (Resolver 사용)
        String clientIp = clientIpResolver.resolve(request);

        long start = System.currentTimeMillis();

        boolean rateLimitEnabled = systemHealthService.isRateLimitEnabled();

        // RateLimit ON일 때만 토큰 검사
        if (rateLimitEnabled) {

            Bucket bucket = bucketStore.get(clientIp, this::createBucket);
            // 토큰 부족 → 요청 차단 (429)
            if (!bucket.tryConsume(1)) {

                rateLimitBlockedCounter.increment();

                log.warn(
                        "event={} traceId={} ip={} uri={}",
                        LogEvent.RATE_LIMITED,
                        traceId,
                        clientIp,
                        request.getRequestURI()
                );

                // Filter 단계에서 즉시 429 응답을 반환하여
                // Controller / Service 계층으로 요청이 전달되지 않도록 조기 차단
                int statusCode = HttpStatus.TOO_MANY_REQUESTS.value();
                response.setStatus(statusCode);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(
                        """
                        {
                          "httpCode": %d,
                          "data": null,
                          "error": {
                            "code": "RATE_LIMIT_EXCEEDED",
                            "message": "Too many requests"
                          }
                        }
                        """.formatted(statusCode)
                );
                response.getWriter().flush();

                long duration = System.currentTimeMillis() - start;

                requestEventBuffer.add(new RequestEvent(
                        Instant.now(),
                        traceId,
                        clientIp,
                        request.getMethod(),
                        request.getRequestURI(),
                        statusCode,
                        LogEvent.RATE_LIMITED,
                        duration
                ));

                return;
            }
            // 토큰 소비 성공 → 요청 허용
            rateLimitAllowedCounter.increment();
        }


        // 항상 요청 실행
        filterChain.doFilter(request, response);

        long duration = System.currentTimeMillis() - start;

        requestEventBuffer.add(new RequestEvent(
                Instant.now(),
                traceId,
                clientIp,
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                LogEvent.REQUEST_COMPLETED,
                duration
        ));

    }

    /**
     * IP별 Rate Limit 정책
     *
     * - burst: 40
     * - refill: 초당 20 토큰
     */
    private Bucket createBucket(String ip) {
        Bandwidth limit = Bandwidth.classic(
                40,
                Refill.intervally(20, Duration.ofSeconds(1))
        );

        return Bucket.builder()
                .addLimit(limit)
                .build();
    }
}
