package com.hanumoka.order.publisher;

import com.hanumoka.order.entity.OutboxEvent;
import com.hanumoka.order.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbox Polling Publisher
 *
 * <h3>동작 원리</h3>
 * <ol>
 *   <li>주기적으로 PENDING 상태의 Outbox 이벤트 조회</li>
 *   <li>Redis Stream으로 이벤트 발행</li>
 *   <li>성공/실패에 따라 상태 업데이트</li>
 * </ol>
 *
 * <h3>다중 인스턴스 안전</h3>
 * <ul>
 *   <li>FOR UPDATE SKIP LOCKED로 조회</li>
 *   <li>이미 다른 인스턴스가 처리 중인 이벤트는 건너뜀</li>
 *   <li>같은 이벤트를 중복 발행하지 않음</li>
 * </ul>
 *
 * <h3>Redis Stream 토픽 매핑</h3>
 * <ul>
 *   <li>Order → stream:order-events</li>
 *   <li>Payment → stream:payment-events</li>
 *   <li>Inventory → stream:inventory-events</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPollingPublisher {

    private final OutboxService outboxService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 한 번에 처리할 이벤트 수
     */
    private static final int BATCH_SIZE = 100;

    /**
     * Stream 키 접두사
     */
    private static final String STREAM_PREFIX = "stream:";

    /**
     * 1초마다 PENDING 이벤트를 Polling하여 발행
     *
     * <h3>처리 흐름</h3>
     * <ol>
     *   <li>claimPendingEvents(): PENDING 조회 + PROCESSING으로 변경 (단일 트랜잭션)</li>
     *   <li>각 이벤트를 Redis Stream으로 발행</li>
     *   <li>성공 시 PUBLISHED, 실패 시 FAILED로 상태 변경</li>
     * </ol>
     *
     * <h3>트랜잭션 경계 문제 해결</h3>
     * <p>claimPendingEvents()에서 PROCESSING으로 변경 후 트랜잭션이 커밋되면,
     * 락이 해제되어도 상태가 PROCESSING이므로 다른 인스턴스가 중복 조회하지 않습니다.</p>
     */
    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        // 1. PENDING 이벤트 조회 + PROCESSING으로 변경 (단일 트랜잭션)
        List<OutboxEvent> events = outboxService.claimPendingEvents(BATCH_SIZE);

        if (events.isEmpty()) {
            return;
        }

        log.debug("Polling {} pending outbox events (claimed as PROCESSING)", events.size());

        // 2. 각 이벤트를 Redis Stream으로 발행
        for (OutboxEvent event : events) {
            try {
                RecordId recordId = publishToRedisStream(event);

                // 3. 발행 성공 → PUBLISHED
                outboxService.markAsPublished(event.getId());

                log.debug("Outbox 이벤트 발행 성공: id={}, eventType={}, recordId={}",
                        event.getId(), event.getEventType(), recordId.getValue());

            } catch (Exception e) {
                // 4. 발행 실패 → FAILED
                log.error("Outbox 이벤트 발행 실패: id={}, eventType={}",
                        event.getId(), event.getEventType(), e);
                outboxService.markAsFailed(event.getId(), e.getMessage());
            }
        }
    }

    /**
     * Redis Stream으로 이벤트 발행
     *
     * @param event Outbox 이벤트
     * @return Record ID
     */
    private RecordId publishToRedisStream(OutboxEvent event) {
        String streamKey = resolveStreamKey(event.getAggregateType());

        // Redis Stream에 저장할 메시지 구성
        Map<String, String> message = new HashMap<>();
        message.put("eventId", String.valueOf(event.getId()));
        message.put("aggregateType", event.getAggregateType());
        message.put("aggregateId", event.getAggregateId());
        message.put("eventType", event.getEventType());
        message.put("payload", event.getPayload());
        message.put("createdAt", event.getCreatedAt().toString());

        // Redis Stream에 추가
        MapRecord<String, String, String> record = StreamRecords.string(message)
                .withStreamKey(streamKey);

        RecordId recordId = redisTemplate.opsForStream().add(record);

        return recordId;
    }

    /**
     * aggregateType에 따라 Stream 키 결정
     *
     * @param aggregateType 도메인 타입
     * @return Redis Stream 키
     */
    private String resolveStreamKey(String aggregateType) {
        return switch (aggregateType.toLowerCase()) {
            case "order" -> STREAM_PREFIX + "order-events";
            case "payment" -> STREAM_PREFIX + "payment-events";
            case "inventory" -> STREAM_PREFIX + "inventory-events";
            default -> STREAM_PREFIX + "domain-events";
        };
    }
}
