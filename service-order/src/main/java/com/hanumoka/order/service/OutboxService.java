package com.hanumoka.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanumoka.order.entity.OutboxEvent;
import com.hanumoka.order.entity.OutboxStatus;
import com.hanumoka.order.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 이벤트 서비스
 *
 * <h3>사용 방법</h3>
 * <pre>
 * // 비즈니스 로직과 같은 트랜잭션 내에서 호출
 * {@literal @}Transactional
 * public void createOrder(Order order) {
 *     orderRepository.save(order);
 *
 *     // 같은 트랜잭션 → 원자성 보장
 *     outboxService.save("Order", order.getId(), "OrderCreated", orderEvent);
 * }
 * </pre>
 *
 * <h3>핵심 원리</h3>
 * <ul>
 *   <li>비즈니스 데이터 + Outbox 이벤트를 같은 트랜잭션으로 저장</li>
 *   <li>커밋되면 둘 다 성공, 롤백되면 둘 다 실패 → 원자성</li>
 *   <li>별도 Publisher가 Outbox 테이블을 Polling하여 발행</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Outbox 이벤트 저장
     *
     * <p>반드시 비즈니스 로직과 같은 트랜잭션 내에서 호출해야 합니다.</p>
     *
     * @param aggregateType 도메인 타입 (예: "Order")
     * @param aggregateId   도메인 ID (예: 주문번호)
     * @param eventType     이벤트 타입 (예: "OrderCreated")
     * @param eventData     이벤트 데이터 (JSON으로 직렬화됨)
     * @return 저장된 Outbox 이벤트
     */
    @Transactional
    public OutboxEvent save(String aggregateType, String aggregateId,
                            String eventType, Object eventData) {
        try {
            String payload = objectMapper.writeValueAsString(eventData);

            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payload)
                    .build();

            OutboxEvent saved = outboxRepository.save(event);
            log.debug("Outbox 이벤트 저장: type={}, aggregateId={}, eventType={}",
                    aggregateType, aggregateId, eventType);

            return saved;

        } catch (JsonProcessingException e) {
            log.error("이벤트 직렬화 실패: {}", eventData, e);
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }

    /**
     * PENDING 이벤트를 조회하고 PROCESSING으로 변경 (Polling Publisher용)
     *
     * <h3>핵심: 트랜잭션 원자성</h3>
     * <ol>
     *   <li>FOR UPDATE SKIP LOCKED로 이벤트 조회 + 락 획득</li>
     *   <li>같은 트랜잭션 내에서 PROCESSING으로 상태 변경</li>
     *   <li>트랜잭션 커밋 → 락 해제</li>
     * </ol>
     *
     * <p>락이 해제된 후에도 상태가 PROCESSING이므로
     * 다른 인스턴스의 폴링 쿼리(WHERE status='PENDING')에 걸리지 않습니다.</p>
     *
     * @param limit 최대 조회 수
     * @return 처리를 위해 claim된 이벤트 목록 (상태: PROCESSING)
     */
    @Transactional
    public List<OutboxEvent> claimPendingEvents(int limit) {
        List<OutboxEvent> events = outboxRepository.findPendingEventsForUpdate(
                PageRequest.of(0, limit));

        // 같은 트랜잭션 내에서 PROCESSING으로 변경
        for (OutboxEvent event : events) {
            event.markAsProcessing();
        }

        if (!events.isEmpty()) {
            log.debug("Outbox 이벤트 {}개 claim 완료 (PENDING → PROCESSING)", events.size());
        }

        return events;
    }

    /**
     * @deprecated Use {@link #claimPendingEvents(int)} instead.
     * 이 메서드는 트랜잭션 경계 문제가 있습니다.
     */
    @Deprecated
    @Transactional
    public List<OutboxEvent> findPendingEvents(int limit) {
        return claimPendingEvents(limit);
    }

    /**
     * 재시도 대상 FAILED 이벤트 조회
     *
     * @param maxRetryCount 최대 재시도 횟수
     * @param limit         최대 조회 수
     * @return 재시도 가능한 FAILED 이벤트 목록
     */
    @Transactional
    public List<OutboxEvent> findFailedEventsForRetry(int maxRetryCount, int limit) {
        return outboxRepository.findFailedEventsForRetry(maxRetryCount, PageRequest.of(0, limit));
    }

    /**
     * 발행 성공 처리
     *
     * @param eventId 이벤트 ID
     */
    @Transactional
    public void markAsPublished(Long eventId) {
        outboxRepository.findById(eventId)
                .ifPresent(event -> {
                    event.markAsPublished();
                    log.debug("Outbox 이벤트 발행 완료: id={}", eventId);
                });
    }

    /**
     * 발행 실패 처리
     *
     * @param eventId 이벤트 ID
     * @param error   에러 메시지
     */
    @Transactional
    public void markAsFailed(Long eventId, String error) {
        outboxRepository.findById(eventId)
                .ifPresent(event -> {
                    event.markAsFailed(error);
                    log.warn("Outbox 이벤트 발행 실패: id={}, error={}, retryCount={}",
                            eventId, error, event.getRetryCount());
                });
    }

    /**
     * 재시도를 위해 PENDING 상태로 변경
     *
     * @param eventId 이벤트 ID
     */
    @Transactional
    public void markForRetry(Long eventId) {
        outboxRepository.findById(eventId)
                .ifPresent(event -> {
                    event.markForRetry();
                    log.info("Outbox 이벤트 재시도 예약: id={}, retryCount={}", eventId, event.getRetryCount());
                });
    }

    /**
     * 타임아웃된 PROCESSING 이벤트 복구
     *
     * <p>Publisher가 비정상 종료되면 PROCESSING 상태로 남아있는 이벤트가 발생합니다.
     * 일정 시간이 지난 PROCESSING 이벤트를 PENDING으로 복구하여 재처리합니다.</p>
     *
     * @param timeoutMinutes 타임아웃 시간 (분)
     * @param limit          최대 복구 수
     * @return 복구된 이벤트 수
     */
    @Transactional
    public int recoverTimedOutEvents(int timeoutMinutes, int limit) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<OutboxEvent> timedOutEvents = outboxRepository.findTimedOutProcessingEvents(
                threshold, PageRequest.of(0, limit));

        for (OutboxEvent event : timedOutEvents) {
            event.markAsTimedOut();
            log.warn("Outbox 이벤트 타임아웃 복구: id={}, processedAt={}, eventType={}",
                    event.getId(), event.getProcessedAt(), event.getEventType());
        }

        return timedOutEvents.size();
    }

    /**
     * 오래된 발행 완료 이벤트 삭제 (정리 스케줄러용)
     *
     * @param daysToKeep 보관 기간 (일)
     * @return 삭제된 이벤트 수
     */
    @Transactional
    public int cleanupOldEvents(int daysToKeep) {
        LocalDateTime threshold = LocalDateTime.now().minusDays(daysToKeep);
        int deleted = outboxRepository.deletePublishedOlderThan(threshold);
        if (deleted > 0) {
            log.info("Outbox 이벤트 정리 완료: {}일 이전 {}개 삭제", daysToKeep, deleted);
        }
        return deleted;
    }

    /**
     * 상태별 이벤트 수 조회 (모니터링용)
     */
    @Transactional(readOnly = true)
    public long countByStatus(OutboxStatus status) {
        return outboxRepository.countByStatus(status);
    }
}
