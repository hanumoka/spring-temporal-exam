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
     * 기본값: 24시간
     */
    long ttlSeconds() default 86400;

    /**
     * Key prefix (Redis key 구분용)
     */
    String prefix() default "idempotency";
}