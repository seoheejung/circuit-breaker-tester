package com.exit8.observability;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 최근 요청 이벤트를 저장하는 메모리 Ring Buffer
 * - 최대 200건 유지
 * - 동기화 기반으로 안전하게 크기 제한 관리
 */
@Component
public class RequestEventBuffer {

    private static final int MAX_SIZE = 200;

    private final ConcurrentLinkedDeque<RequestEvent> buffer =
            new ConcurrentLinkedDeque<>();

    public synchronized void add(RequestEvent event) {
        buffer.addFirst(event);

        // 사이즈를 넘었는지 바로 체크해서 정리
        // MAX_SIZE를 초과하는 순간 즉시 제거하므로 항상 일정한 크기를 유지
        while (buffer.size() > MAX_SIZE) {
            buffer.removeLast();
        }
    }

    public List<RequestEvent> getRecent(int limit) {
        return buffer.stream().limit(limit).toList();
    }
}
