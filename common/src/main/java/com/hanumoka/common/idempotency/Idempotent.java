package com.hanumoka.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 멱등성 보장 어노테이션
 *
 * 이 어노테이션이 붙은 메서드는 동일한 idempotencyKey로 호출 시
 * 캐시된 응답을 반환합니다.
 *
 * @see <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/">IETF Idempotency-Key Header</a>
 * @see <a href="https://stripe.com/blog/idempotency">Stripe Idempotency</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * Idempotency Key를 추출할 헤더 이름
     */
    String headerName() default "X-Idempotency-Key";

    /**
     * 캐시 유지 시간 (초)
     * 기본값: 24시간 (Stripe 기준)
     */
    long ttlSeconds() default 86400;

    /**
     * Key prefix (Redis key 구분용)
     */
    String prefix() default "idempotency";

    /**
     * Idempotency Key 필수 여부
     *
     * true: Key가 없으면 400 Bad Request 반환 (결제, 주문 등 중요 API)
     * false: Key가 없으면 멱등성 체크 없이 실행 (일반 API)
     *
     * IETF 표준: 리소스가 필수 여부를 결정하고 문서화해야 함
     */
    boolean required() default true;
}