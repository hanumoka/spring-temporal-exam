package com.hanumoka.order.repository;

import com.hanumoka.order.entity.OutboxEvent;
import com.hanumoka.order.entity.OutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 이벤트 레포지토리
 *
 * <h3>핵심 쿼리</h3>
 * <ul>
 *   <li>{@link #findPendingEventsForUpdate} - FOR UPDATE SKIP LOCKED로 다중 인스턴스 안전</li>
 *   <li>{@link #findFailedEventsForRetry} - 재시도 대상 이벤트 조회</li>
 *   <li>{@link #deletePublishedOlderThan} - 오래된 발행 완료 이벤트 정리</li>
 * </ul>
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * PENDING 상태 이벤트 조회 (FOR UPDATE SKIP LOCKED)
     *
     * <p>다중 인스턴스 환경에서 안전한 Polling:</p>
     * <ul>
     *   <li>FOR UPDATE: 조회한 row에 배타적 락 획득</li>
     *   <li>SKIP LOCKED: 이미 락된 row는 건너뜀 (대기 없음)</li>
     *   <li>결과: 같은 이벤트를 중복 처리하지 않음</li>
     * </ul>
     *
     * @param pageable 페이징 (limit 포함)
     * @return PENDING 상태이고 락 획득 가능한 이벤트 목록
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")}) // SKIP LOCKED
    List<OutboxEvent> findPendingEventsForUpdate(Pageable pageable);

    /**
     * 상태별 이벤트 조회
     */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    /**
     * 재시도 대상 FAILED 이벤트 조회
     *
     * @param maxRetryCount 최대 재시도 횟수 (이 값 미만인 것만)
     * @param pageable      페이징
     * @return 재시도 가능한 FAILED 이벤트 목록
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'FAILED' " +
            "AND e.retryCount < :maxRetryCount ORDER BY e.retryCount ASC, e.createdAt ASC")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    List<OutboxEvent> findFailedEventsForRetry(
            @Param("maxRetryCount") int maxRetryCount,
            Pageable pageable);

    /**
     * 오래된 발행 완료 이벤트 삭제
     *
     * @param threshold 이 시간 이전에 발행된 이벤트 삭제
     * @return 삭제된 row 수
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = 'PUBLISHED' AND e.publishedAt < :threshold")
    int deletePublishedOlderThan(@Param("threshold") LocalDateTime threshold);

    /**
     * PROCESSING 상태에서 타임아웃된 이벤트 조회
     *
     * <p>Publisher가 비정상 종료되면 PROCESSING 상태로 남아있는 이벤트가 생김.
     * 일정 시간(예: 5분) 이상 PROCESSING 상태인 이벤트를 PENDING으로 복구.</p>
     *
     * @param threshold 이 시간 이전에 PROCESSING된 이벤트를 타임아웃으로 간주
     * @param pageable  페이징
     * @return 타임아웃된 PROCESSING 이벤트 목록
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PROCESSING' " +
            "AND e.processedAt < :threshold ORDER BY e.processedAt ASC")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    List<OutboxEvent> findTimedOutProcessingEvents(
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable);

    /**
     * 특정 aggregate의 이벤트 조회 (디버깅/모니터링용)
     */
    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtDesc(
            String aggregateType, String aggregateId);

    /**
     * 상태별 이벤트 수 조회 (모니터링용)
     */
    long countByStatus(OutboxStatus status);

    /**
     * 최대 재시도 횟수를 초과한 FAILED 이벤트 조회 (DLQ 이동 대상)
     *
     * <p>재시도 횟수가 maxRetryCount 이상인 FAILED 이벤트를 조회합니다.
     * 이 이벤트들은 DLQ로 이동 후 원본 테이블에서 삭제됩니다.</p>
     *
     * <p>다중 인스턴스 환경에서 같은 이벤트를 중복 처리하지 않도록
     * FOR UPDATE SKIP LOCKED를 사용합니다.</p>
     *
     * @param maxRetryCount 최대 재시도 횟수 (이 값 이상인 것만)
     * @param pageable      페이징
     * @return DLQ 이동 대상 이벤트 목록
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'FAILED' " +
            "AND e.retryCount >= :maxRetryCount ORDER BY e.createdAt ASC")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    List<OutboxEvent> findExhaustedFailedEvents(
            @Param("maxRetryCount") int maxRetryCount,
            Pageable pageable);
}
