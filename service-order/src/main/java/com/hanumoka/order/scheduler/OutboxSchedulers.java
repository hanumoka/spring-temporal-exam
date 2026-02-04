package com.hanumoka.order.scheduler;

import com.hanumoka.order.entity.OutboxEvent;
import com.hanumoka.order.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 관련 스케줄러
 *
 * <h3>스케줄러 목록</h3>
 * <ul>
 *   <li>{@link #retryFailedEvents} - 실패한 이벤트 재시도 (5분마다)</li>
 *   <li>{@link #recoverTimedOutEvents} - PROCESSING 타임아웃 복구 (1분마다)</li>
 *   <li>{@link #cleanupOldEvents} - 오래된 이벤트 정리 (매일 새벽 2시)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxSchedulers {

    private final OutboxService outboxService;

    /**
     * 최대 재시도 횟수
     */
    private static final int MAX_RETRY_COUNT = 5;

    /**
     * 재시도 배치 크기
     */
    private static final int RETRY_BATCH_SIZE = 50;

    /**
     * DLQ 이동 배치 크기
     */
    private static final int DLQ_BATCH_SIZE = 100;

    /**
     * 발행 완료 이벤트 보관 기간 (일)
     */
    private static final int DAYS_TO_KEEP = 7;

    /**
     * PROCESSING 타임아웃 시간 (분)
     *
     * <p>Publisher가 비정상 종료된 경우,
     * 이 시간이 지난 PROCESSING 이벤트는 PENDING으로 복구</p>
     */
    private static final int PROCESSING_TIMEOUT_MINUTES = 5;

    /**
     * 타임아웃 복구 배치 크기
     */
    private static final int TIMEOUT_BATCH_SIZE = 100;

    /**
     * 실패한 이벤트 재시도 (5분마다)
     *
     * <h3>처리 순서</h3>
     * <ol>
     *   <li>최대 재시도 초과 이벤트 → DLQ 이동 (먼저 처리)</li>
     *   <li>재시도 가능 이벤트 → 지수 백오프 후 PENDING으로 변경</li>
     * </ol>
     *
     * <h3>지수 백오프</h3>
     * <ul>
     *   <li>1회 실패: 2분 후 재시도</li>
     *   <li>2회 실패: 4분 후 재시도</li>
     *   <li>3회 실패: 8분 후 재시도</li>
     *   <li>4회 실패: 16분 후 재시도</li>
     *   <li>5회 실패: DLQ 이동</li>
     * </ul>
     */
    @Scheduled(fixedRate = 300000) // 5분마다
    public void retryFailedEvents() {
        // 1. 먼저 exhausted 이벤트를 DLQ로 이동
        // (로그는 OutboxService에서 출력)
        outboxService.moveExhaustedEventsToDlq(MAX_RETRY_COUNT, DLQ_BATCH_SIZE);

        // 2. 재시도 가능한 이벤트 처리
        List<OutboxEvent> failedEvents = outboxService.findFailedEventsForRetry(
                MAX_RETRY_COUNT, RETRY_BATCH_SIZE);

        if (failedEvents.isEmpty()) {
            return;
        }

        log.info("재시도 대상 Outbox 이벤트: {}개", failedEvents.size());

        int retried = 0;

        for (OutboxEvent event : failedEvents) {
            // 지수 백오프 체크
            if (shouldRetry(event)) {
                outboxService.markForRetry(event.getId());
                retried++;
            }
        }

        if (retried > 0) {
            log.info("Outbox 재시도 예약 완료: {}개", retried);
        }
    }

    /**
     * 지수 백오프에 따라 재시도 가능 여부 확인
     *
     * <p>마지막 실패 시간(lastFailedAt)을 기준으로 계산합니다.</p>
     *
     * @param event Outbox 이벤트
     * @return 재시도 가능하면 true
     */
    private boolean shouldRetry(OutboxEvent event) {
        LocalDateTime lastFailedAt = event.getLastFailedAt();

        // lastFailedAt이 없으면 즉시 재시도 (이전 버전 호환)
        if (lastFailedAt == null) {
            return true;
        }

        // 2^retryCount 분 후 재시도
        // retryCount=1 → 2분, retryCount=2 → 4분, retryCount=3 → 8분
        long waitMinutes = (long) Math.pow(2, event.getRetryCount());
        LocalDateTime retryAfter = lastFailedAt.plus(Duration.ofMinutes(waitMinutes));

        return LocalDateTime.now().isAfter(retryAfter);
    }

    /**
     * PROCESSING 타임아웃 복구 (1분마다)
     *
     * <h3>Why?</h3>
     * <p>Publisher가 비정상 종료되면 PROCESSING 상태로 남아있는 이벤트가 발생합니다.
     * 이 스케줄러가 일정 시간(5분)이 지난 PROCESSING 이벤트를 PENDING으로 복구합니다.</p>
     *
     * <h3>시나리오</h3>
     * <ol>
     *   <li>Publisher A가 이벤트를 PROCESSING으로 변경</li>
     *   <li>Publisher A가 Redis 발행 전 비정상 종료</li>
     *   <li>이벤트는 PROCESSING 상태로 남아있음</li>
     *   <li>이 스케줄러가 5분 후 PENDING으로 복구</li>
     *   <li>다른 Publisher가 정상 처리</li>
     * </ol>
     */
    @Scheduled(fixedRate = 60000) // 1분마다
    public void recoverTimedOutEvents() {
        int recovered = outboxService.recoverTimedOutEvents(
                PROCESSING_TIMEOUT_MINUTES, TIMEOUT_BATCH_SIZE);

        if (recovered > 0) {
            log.warn("PROCESSING 타임아웃 이벤트 복구 완료: {}개", recovered);
        }
    }

    /**
     * 오래된 발행 완료 이벤트 정리 (매일 새벽 2시)
     *
     * <p>7일 이상 된 PUBLISHED 상태 이벤트 삭제</p>
     */
    @Scheduled(cron = "0 0 2 * * *") // 매일 새벽 2시
    public void cleanupOldEvents() {
        log.info("Outbox 이벤트 정리 시작 ({}일 이전)", DAYS_TO_KEEP);
        int deleted = outboxService.cleanupOldEvents(DAYS_TO_KEEP);
        log.info("Outbox 이벤트 정리 완료: {}개 삭제", deleted);
    }
}
