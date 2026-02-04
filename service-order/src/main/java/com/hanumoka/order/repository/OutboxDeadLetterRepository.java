package com.hanumoka.order.repository;

import com.hanumoka.order.entity.OutboxDeadLetter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox Dead Letter Queue 레포지토리
 *
 * <h3>주요 조회 용도</h3>
 * <ul>
 *   <li>미해결 DLQ 조회 (운영자 대시보드)</li>
 *   <li>특정 aggregate의 실패 이력 조회</li>
 *   <li>기간별 DLQ 통계</li>
 * </ul>
 */
public interface OutboxDeadLetterRepository extends JpaRepository<OutboxDeadLetter, Long> {

    /**
     * 미해결 DLQ 조회 (운영자 대시보드용)
     *
     * @param pageable 페이징
     * @return 미해결 DLQ 목록
     */
    List<OutboxDeadLetter> findByResolvedFalseOrderByFailedAtDesc(Pageable pageable);

    /**
     * 특정 aggregate의 DLQ 조회
     *
     * @param aggregateType 도메인 타입
     * @param aggregateId   도메인 ID
     * @return 해당 aggregate의 DLQ 목록
     */
    List<OutboxDeadLetter> findByAggregateTypeAndAggregateIdOrderByFailedAtDesc(
            String aggregateType, String aggregateId);

    /**
     * 미해결 DLQ 수 조회 (모니터링/알림용)
     *
     * @return 미해결 DLQ 수
     */
    long countByResolvedFalse();

    /**
     * 이벤트 타입별 미해결 DLQ 수 조회
     *
     * @param eventType 이벤트 타입
     * @return 해당 타입의 미해결 DLQ 수
     */
    long countByEventTypeAndResolvedFalse(String eventType);

    /**
     * 기간별 DLQ 조회
     *
     * @param start 시작 시간
     * @param end   종료 시간
     * @return 해당 기간의 DLQ 목록
     */
    List<OutboxDeadLetter> findByFailedAtBetweenOrderByFailedAtDesc(
            LocalDateTime start, LocalDateTime end);

    /**
     * 원본 이벤트 ID로 DLQ 조회
     *
     * @param originalId 원본 outbox_event ID
     * @return DLQ (있으면)
     */
    boolean existsByOriginalId(Long originalId);
}
