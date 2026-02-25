package com.exit8.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {
    //  프록시(L4, Nginx 등) 환경에서도 실제 사용자 IP를 찾아내는 로직
    public String resolve(HttpServletRequest request) {
        // 프록시 서버를 거칠 경우 원래 IP가 담기는 X-Forwarded-For 헤더 확인
        String xff = request.getHeader("X-Forwarded-For");

        if (xff != null && !xff.isBlank()) {
            // 여러 프록시를 거친 경우 첫 번째 IP가 실제 클라이언트 IP임
            return xff.split(",")[0].trim();
        }
        // 헤더가 없으면 직접 연결된 장치의 IP 반환
        return request.getRemoteAddr();
    }
}