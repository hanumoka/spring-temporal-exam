package com.hanumoka.common.exception;

import com.hanumoka.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 처리기
 *
 * <h2>설계 원칙</h2>
 * <pre>
 * 1. 예외 핸들러 우선순위
 *    - Spring은 더 구체적인 예외 핸들러를 먼저 매칭
 *    - BusinessException > OptimisticLockingFailureException > Exception
 *
 * 2. RuntimeException 핸들러를 별도로 두지 않는 이유
 *    - BusinessException이 RuntimeException의 하위 클래스
 *    - RuntimeException 핸들러는 불필요한 중간 레이어
 *    - 인프라/시스템 오류(락 실패, 서비스 장애)는 500으로 처리하는 것이 적절
 *
 * 3. 예외 분류
 *    - BusinessException: 비즈니스 로직 오류 (400 Bad Request)
 *    - OptimisticLockingFailureException: 동시성 충돌 (409 Conflict)
 *    - 기타 Exception: 시스템 오류 (500 Internal Server Error)
 *
 * 4. 확장 시
 *    - 세분화 필요 시 LockAcquisitionException, ServiceUnavailableException 등 생성
 *    - 각 예외에 맞는 HTTP 상태 코드와 핸들러 추가
 * </pre>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외 처리 (400 Bad Request)
     * <p>
     * 비즈니스 규칙 위반 시 발생하는 예외를 처리합니다.
     * 예: 재고 부족, 주문 없음, 결제 실패 등
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("비즈니스 예외 발생: code={}, message={}",
                e.getErrorInfo().getCode(), e.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(e.getErrorInfo()));
    }

    /**
     * 낙관적 락 충돌 처리 (409 Conflict)
     * <p>
     * JPA @Version을 통한 낙관적 락 충돌 시 발생합니다.
     * RLock(분산 락)이 정상 동작하면 발생하지 않으며,
     * RLock 실패 시 최후 방어선으로 동작합니다.
     * <p>
     * 클라이언트는 이 응답을 받으면 재시도해야 합니다.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockException(
            OptimisticLockingFailureException e) {
        log.warn("낙관적 락 충돌 발생: {}", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("OPTIMISTIC_LOCK_CONFLICT",
                        "다른 요청이 먼저 처리되었습니다. 다시 시도해주세요."));
    }

    /**
     * 기타 모든 예외 처리 (500 Internal Server Error)
     * <p>
     * 위에서 처리되지 않은 모든 예외를 처리합니다.
     * RuntimeException 포함 (락 획득 실패, 서비스 장애, 인터럽트 등)
     * <p>
     * 주의: 예외 메시지를 클라이언트에 노출하지 않고,
     * 일반적인 에러 메시지를 반환합니다. (보안)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("예외 발생: ", e);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.toErrorInfo()));
    }
}
