package com.hanumoka.common.idempotency;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Idempotency Key 관리 서비스
 * Redis를 사용하여 분산 환경에서도 동작
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /**
     * 이미 처리된 요청인지 확인하고, 처리되었다면 캐시된 응답 반환
     */
    public Optional<String> getIfProcessed(String key) {
        RBucket<String> bucket = redissonClient.getBucket(key);
        String cachedResponse = bucket.get();

        if (cachedResponse != null && !"PROCESSING".equals(cachedResponse)) {
            log.info("[Idempotency] 캐시된 응답 반환 - key: {}", key);
            return Optional.of(cachedResponse);
        }

        return Optional.empty();
    }

    /**
     * 처리 결과를 캐시에 저장
     */
    public void saveResponse(String key, Object response, long ttlSeconds) {
        try {
            String jsonResponse = objectMapper.writeValueAsString(response);
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(jsonResponse, Duration.ofSeconds(ttlSeconds));
            log.info("[Idempotency] 응답 캐시 저장 - key: {}, ttl: {}초", key, ttlSeconds);
        } catch (JsonProcessingException e) {
            log.error("[Idempotency] 응답 직렬화 실패 - key: {}", key, e);
        }
    }

    /**
     * 처리 중 상태로 마킹 (중복 요청 동시 처리 방지)
     * @return true: 마킹 성공 (첫 요청), false: 이미 처리 중
     */
    public boolean markAsProcessing(String key, long ttlSeconds) {
        RBucket<String> bucket = redissonClient.getBucket(key);
        boolean success = bucket.setIfAbsent("PROCESSING", Duration.ofSeconds(ttlSeconds));

        if (success) {
            log.info("[Idempotency] 처리 시작 마킹 - key: {}", key);
        } else {
            log.warn("[Idempotency] 이미 처리 중인 요청 - key: {}", key);
        }

        return success;
    }

    /**
     * 캐시 키 생성
     */
    public String buildKey(String prefix, String idempotencyKey) {
        return prefix + ":" + idempotencyKey;
    }
}