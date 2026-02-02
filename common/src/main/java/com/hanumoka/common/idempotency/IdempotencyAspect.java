package com.hanumoka.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanumoka.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * @Idempotent 어노테이션 처리 AOP
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyAspect {

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(idempotent)")
    public Object handleIdempotency(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws
            Throwable {
        // 1. HTTP 요청에서 Idempotency Key 추출
        String idempotencyKey = extractIdempotencyKey(idempotent.headerName());

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            if (idempotent.required()) {
                // Key 필수인데 없으면 400 Bad Request (IETF 표준)
                log.warn("[Idempotency] 필수 Key 누락 - header: {}", idempotent.headerName());
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.fail(
                                "IDEMPOTENCY_KEY_REQUIRED",
                                "Idempotency Key is required. Please provide '" + idempotent.headerName() + "' header."
                        ));
            }
            // Key가 선택인데 없으면 그냥 실행 (멱등성 미적용)
            log.debug("[Idempotency] Key 없음 - 일반 처리");
            return joinPoint.proceed();
        }

        String cacheKey = idempotencyService.buildKey(idempotent.prefix(), idempotencyKey);

        // 2. 이미 처리된 요청인지 확인
        Optional<String> cachedResponse = idempotencyService.getIfProcessed(cacheKey);
        if (cachedResponse.isPresent()) {
            log.info("[Idempotency] 중복 요청 감지 - key: {}", idempotencyKey);
            return deserializeResponse(cachedResponse.get());
        }

        // 3. 처리 중 마킹 (동시 요청 방지)
        if (!idempotencyService.markAsProcessing(cacheKey, idempotent.ttlSeconds())) {
            log.warn("[Idempotency] 동시 요청 감지 - key: {}", idempotencyKey);
            // 잠시 대기 후 캐시 확인 (처리 완료 대기)
            Thread.sleep(100);
            cachedResponse = idempotencyService.getIfProcessed(cacheKey);
            if (cachedResponse.isPresent()) {
                return deserializeResponse(cachedResponse.get());
            }
        }

        // 4. 비즈니스 로직 실행
        Object result = joinPoint.proceed();

        // 5. 결과 캐시 저장
        idempotencyService.saveResponse(cacheKey, result, idempotent.ttlSeconds());

        return result;
    }

    private String extractIdempotencyKey(String headerName) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attributes == null) {
            return null;
        }

        HttpServletRequest request = attributes.getRequest();
        return request.getHeader(headerName);
    }

    private Object deserializeResponse(String json) {
        try {
            // ResponseEntity<ApiResponse<?>> 타입으로 역직렬화
            ApiResponse<?> apiResponse = objectMapper.readValue(json, ApiResponse.class);
            return ResponseEntity.ok(apiResponse);
        } catch (Exception e) {
            log.error("[Idempotency] 응답 역직렬화 실패", e);
            throw new RuntimeException("캐시된 응답 복원 실패", e);
        }
    }
}