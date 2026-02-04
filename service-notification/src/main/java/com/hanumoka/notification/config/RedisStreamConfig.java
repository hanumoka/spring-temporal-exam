package com.hanumoka.notification.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis Stream Consumer Group 설정
 *
 * <h3>Consumer Group이란?</h3>
 * <ul>
 *   <li>같은 그룹 내 Consumer들이 메시지를 분산 처리</li>
 *   <li>메시지는 한 Consumer에게만 전달됨 (중복 처리 방지)</li>
 *   <li>ACK 전까지 Pending Entry List(PEL)에 보관</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class RedisStreamConfig {

    private final StringRedisTemplate redisTemplate;

    @Value("${notification.stream.key}")
    private String streamKey;

    @Value("${notification.stream.consumer-group}")
    private String consumerGroup;

    /**
     * Consumer Group 생성 (멱등성 보장)
     *
     * <p>이미 존재하는 경우 무시 (BUSYGROUP 에러)</p>
     */
    @PostConstruct
    public void createConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), consumerGroup);
            log.info("Redis Stream Consumer Group 생성 완료: stream={}, group={}",
                    streamKey, consumerGroup);
        } catch (RedisSystemException e) {
            if (e.getCause() != null && e.getCause().getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer Group 이미 존재: stream={}, group={}", streamKey, consumerGroup);
            } else if (e.getCause() != null && e.getCause().getMessage().contains("no such key")) {
                // Stream이 아직 없는 경우 - 첫 메시지가 올 때 자동 생성됨
                log.info("Stream이 아직 존재하지 않음: {}. 첫 메시지 발행 시 생성됩니다.", streamKey);
            } else {
                throw e;
            }
        }
    }
}
