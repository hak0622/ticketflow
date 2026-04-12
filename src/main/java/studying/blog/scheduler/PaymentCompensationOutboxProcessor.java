package studying.blog.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.Concert;
import studying.blog.domain.OutboxStatus;
import studying.blog.domain.PaymentCompensationOutbox;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.repository.PaymentCompensationOutboxRepository;

import java.util.Map;

/**
 * Outbox 1건을 독립 트랜잭션(REQUIRES_NEW)으로 처리한다.
 * PaymentCompensationScheduler가 self-invocation 없이 프록시를 거쳐
 * 각 건을 별도 커밋/롤백할 수 있도록 분리한 Bean.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompensationOutboxProcessor {

    private final PaymentCompensationOutboxRepository outboxRepository;
    private final BookingRepository bookingRepository;
    private final ConcertRepository concertRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private static final int MAX_RETRY = 3;

    // Outbox가 최종 FAILED 상태로 전환된 횟수 — 운영 알림 기준 지표
    private Counter outboxFailedCounter() {
        return meterRegistry.counter("outbox.failed");
    }

    /**
     * outboxId를 받아 REQUIRES_NEW 트랜잭션 내에서 직접 조회한다.
     * 호출 측(processPending)에는 트랜잭션이 없으므로 findByStatus()가 반환한
     * 엔티티는 detached 상태다. 그 객체를 그대로 넘기면 이 트랜잭션의
     * persistence context에 등록되지 않아 dirty checking이 동작하지 않는다.
     * findById()로 재조회해야 managed 엔티티가 되어 변경사항이 DB에 반영된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long outboxId) throws Exception {
        PaymentCompensationOutbox outbox = outboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox not found: " + outboxId));

        Map<String, Object> payload = objectMapper.readValue(
                outbox.getPayload(), new TypeReference<>() {}
        );
        Long bookingId = ((Number) payload.get("bookingId")).longValue();
        Long concertId = ((Number) payload.get("concertId")).longValue();

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        // 멱등: 이미 취소된 예약이면 좌석 반납 없이 outbox만 완료 처리
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            outbox.markPublished();
            log.info("[OUTBOX][SKIP] outboxId={} bookingId={} reason=already_cancelled",
                    outboxId, bookingId);
            return;
        }

        Concert concert = concertRepository.findByIdForUpdate(concertId)
                .orElseThrow(() -> new IllegalArgumentException("Concert not found: " + concertId));

        booking.cancel();
        concert.decreaseBooked();
        outbox.markPublished();

        log.info("[OUTBOX][PUBLISHED] outboxId={} bookingId={} concertId={} bookedCount={}",
                outboxId, bookingId, concertId, concert.getBookedCount());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRetry(Long outboxId, Exception cause) {
        PaymentCompensationOutbox outbox = outboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox not found: " + outboxId));

        outbox.incrementRetry(MAX_RETRY);
        boolean isFinal = outbox.getStatus() == OutboxStatus.FAILED;
        log.warn("[OUTBOX][{}] outboxId={} retryCount={} error={}",
                isFinal ? "FAILED" : "RETRY",
                outboxId, outbox.getRetryCount(), cause.getMessage());

        // 재시도 소진 → 최종 FAILED 확정 시에만 증가
        if (isFinal) {
            outboxFailedCounter().increment();
        }
    }
}
