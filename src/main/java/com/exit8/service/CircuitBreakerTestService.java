package com.exit8.service;

import com.exit8.config.constants.CircuitNames;
import com.exit8.exception.ApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CircuitBreakerTestService {

    @CircuitBreaker(name = CircuitNames.TEST_CIRCUIT)
    public String callWithDelay() {
        try {
            // 일부러 timeout 유도
            Thread.sleep(3000);
            return "OK";

        } catch (InterruptedException e) {
            // 인터럽트 상태 복원
            Thread.currentThread().interrupt();

            throw new ApiException(
                    "THREAD_INTERRUPTED",
                    "thread was interrupted during processing",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

}
