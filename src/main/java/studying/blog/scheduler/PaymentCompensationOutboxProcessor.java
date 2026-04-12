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

    private final BookingRepository bookingRepository;
    private final ConcertRepository concertRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private static final int MAX_RETRY = 3;

    // Outbox가 최종 FAILED 상태로 전환된 횟수 — 운영 알림 기준 지표
    private Counter outboxFailedCounter() {
        return meterRegistry.counter("outbox.failed");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(PaymentCompensationOutbox outbox) throws Exception {
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
                    outbox.getId(), bookingId);
            return;
        }

        Concert concert = concertRepository.findByIdForUpdate(concertId)
                .orElseThrow(() -> new IllegalArgumentException("Concert not found: " + concertId));

        booking.cancel();
        concert.decreaseBooked();
        outbox.markPublished();

        log.info("[OUTBOX][PUBLISHED] outboxId={} bookingId={} concertId={} bookedCount={}",
                outbox.getId(), bookingId, concertId, concert.getBookedCount());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRetry(PaymentCompensationOutbox outbox, Exception cause) {
        outbox.incrementRetry(MAX_RETRY);
        boolean isFinal = outbox.getStatus() == OutboxStatus.FAILED;
        log.warn("[OUTBOX][{}] outboxId={} retryCount={} error={}",
                isFinal ? "FAILED" : "RETRY",
                outbox.getId(), outbox.getRetryCount(), cause.getMessage());

        // 재시도 소진 → 최종 FAILED 확정 시에만 증가
        if (isFinal) {
            outboxFailedCounter().increment();
        }
    }
}
