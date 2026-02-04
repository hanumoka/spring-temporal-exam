package com.hanumoka.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청별 traceId를 MDC에 설정하는 필터
 *
 * 동작:
 * 1. X-Request-ID 헤더가 있으면 → 해당 값 사용 (서비스 간 전파)
 * 2. 없으면 → 새로 생성 (최초 진입점)
 *
 * MDC 키:
 * - traceId: 요청 추적 ID (X-Request-ID 헤더와 매핑)
 * - sagaId: Saga 식별자 (쿼리 파라미터에서 추출)
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SAGA_ID = "sagaId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. traceId 설정 (헤더 우선, 없으면 생성)
            String traceId = request.getHeader(REQUEST_ID_HEADER);
            if (traceId == null || traceId.isBlank()) {
                traceId = generateTraceId();
            }
            MDC.put(MDC_TRACE_ID, traceId);

            // 2. sagaId 설정 (있으면)
            String sagaId = request.getParameter("sagaId");
            if (sagaId != null && !sagaId.isBlank()) {
                MDC.put(MDC_SAGA_ID, sagaId);
            }

            // 3. 응답 헤더에도 추가 (디버깅용)
            response.setHeader(REQUEST_ID_HEADER, traceId);

            filterChain.doFilter(request, response);
        } finally {
            // 4. 요청 완료 후 반드시 정리 (메모리 누수 방지)
            MDC.clear();
        }
    }

    private String generateTraceId() {
        return "REQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
