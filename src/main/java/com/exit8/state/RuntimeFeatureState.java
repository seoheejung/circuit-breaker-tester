package com.exit8.state;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RuntimeFeatureState {

    private final AtomicBoolean rateLimitEnabled;
    private final AtomicBoolean redisCacheEnabled;

    public RuntimeFeatureState(
            @Value("${rate-limit.enabled:false}") boolean rateInit,
            @Value("${redis-cache.enabled:false}") boolean redisInit
    ) {
        this.rateLimitEnabled = new AtomicBoolean(rateInit);
        this.redisCacheEnabled = new AtomicBoolean(redisInit);
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled.get();
    }
    public boolean isRedisCacheEnabled() { return redisCacheEnabled.get();  }

    private static boolean toggle(AtomicBoolean flag) {
        while (true) {
            // 현재 값을 읽어 expected 값으로 사용
            boolean prev = flag.get();

            // 토글될 새로운 값 계산
            boolean next = !prev;

            /*
             * 현재 값이 prev와 동일하면 next로 변경(CAS 성공 → true 반환)
             * 다른 스레드가 값을 변경했다면 CAS 실패 → false 반환
             * 실패 시 최신 값을 다시 읽어 재시도
             */
            if (flag.compareAndSet(prev, next)) {
                return next;
            }
        }
    }

    public boolean toggleRateLimit() { return toggle(rateLimitEnabled); }
    public boolean toggleRedisCache() { return toggle(redisCacheEnabled); }

}

