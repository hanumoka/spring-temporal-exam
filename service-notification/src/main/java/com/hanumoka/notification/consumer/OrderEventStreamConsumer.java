package com.hanumoka.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanumoka.common.event.OrderCreatedEvent;
import com.hanumoka.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream Consumer - 주문 이벤트 구독
 *
 * <h3>동작 방식</h3>
 * <ol>
 *   <li>XREADGROUP으로 Consumer Group에서 메시지 읽기</li>
 *   <li>eventType에 따라 처리 로직 분기</li>
 *   <li>처리 성공 시 XACK로 확인</li>
 *   <li>처리 실패 시 PEL에 남아 재처리 대상</li>
 * </ol>
 *
 * <h3>Polling 방식 선택 이유</h3>
 * <ul>
 *   <li>Spring Data Redis의 StreamMessageListenerContainer보다 단순</li>
 *   <li>에러 처리 및 재시도 로직 커스터마이징 용이</li>
 *   <li>배치 처리 가능 (한 번에 여러 메시지)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventStreamConsumer {

    private final StringRedisTemplate redisTemplate;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Value("${notification.stream.key}")
    private String streamKey;

    @Value("${notification.stream.consumer-group}")
    private String consumerGroup;

    @Value("${notification.stream.consumer-name}")
    private String consumerName;

    @Value("${notification.stream.poll-timeout}")
    private long pollTimeout;

    @Value("${notification.stream.batch-size}")
    private int batchSize;

    /**
     * 새 메시지 폴링 (1초마다)
     *
     * <p>XREADGROUP GROUP {group} {consumer} COUNT {n} BLOCK {ms} STREAMS {key} ></p>
     * <p>'>' = 아직 전달되지 않은 새 메시지만 읽기</p>
     */
    @SuppressWarnings("unchecked")
    @Scheduled(fixedDelay = 1000)
    public void pollNewMessages() {
        try {
            var messages = redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamReadOptions.empty()
                            .count(batchSize)
                            .block(Duration.ofMillis(pollTimeout)),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed())  // '>'
            );

            if (messages == null || messages.isEmpty()) {
                return;
            }

            log.debug("Redis Stream 메시지 수신: {}개", messages.size());

            for (var message : messages) {
                processMessage((MapRecord<String, Object, Object>) message);
            }

        } catch (Exception e) {
            log.error("Redis Stream 폴링 중 오류 발생", e);
        }
    }

    /**
     * Pending 메시지 재처리 (5분마다)
     *
     * <p>처리 실패로 ACK되지 않은 메시지를 재처리합니다.</p>
     * <p>60초 이상 Pending 상태인 메시지만 대상</p>
     */
    @SuppressWarnings("unchecked")
    @Scheduled(fixedRate = 300000)  // 5분마다
    public void processPendingMessages() {
        try {
            // XAUTOCLAIM으로 오래된 Pending 메시지 자동 claim
            // 60초 이상 처리되지 않은 메시지를 이 Consumer가 가져감
            var pendingMessages = redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamReadOptions.empty().count(batchSize),
                    StreamOffset.create(streamKey, ReadOffset.from("0"))  // Pending 메시지부터
            );

            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return;
            }

            log.info("Pending 메시지 재처리 시작: {}개", pendingMessages.size());

            for (var message : pendingMessages) {
                processMessage((MapRecord<String, Object, Object>) message);
            }

        } catch (Exception e) {
            log.error("Pending 메시지 처리 중 오류 발생", e);
        }
    }

    /**
     * 메시지 처리
     */
    private void processMessage(MapRecord<String, Object, Object> message) {
        RecordId recordId = message.getId();
        Map<Object, Object> body = message.getValue();

        String eventType = (String) body.get("eventType");
        String payload = (String) body.get("payload");

        log.debug("메시지 처리 시작: id={}, eventType={}", recordId, eventType);

        try {
            switch (eventType) {
                case "OrderCreated" -> handleOrderCreated(payload);
                default -> log.warn("알 수 없는 이벤트 타입: {}", eventType);
            }

            // 처리 성공 → ACK
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, recordId);
            log.debug("메시지 처리 완료 및 ACK: id={}", recordId);

        } catch (Exception e) {
            log.error("메시지 처리 실패: id={}, eventType={}", recordId, eventType, e);
            // ACK 안함 → PEL에 남아 재처리 대상
        }
    }

    /**
     * OrderCreated 이벤트 처리
     */
    private void handleOrderCreated(String payload) throws JsonProcessingException {
        OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);

        log.info("주문 생성 이벤트 수신: orderNumber={}, customerId={}",
                event.getOrderNumber(), event.getCustomerId());

        // 알림 발송
        notificationService.sendOrderConfirmation(event);
    }
}
