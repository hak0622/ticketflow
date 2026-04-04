package studying.blog.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.Concert;
import studying.blog.domain.OutboxStatus;
import studying.blog.domain.PaymentCompensationOutbox;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.repository.PaymentCompensationOutboxRepository;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompensationScheduler {

    private final PaymentCompensationOutboxRepository outboxRepository;
    private final BookingRepository bookingRepository;
    private final ConcertRepository concertRepository;
    private final ObjectMapper objectMapper;

    private static final int MAX_RETRY = 3;

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void processPending() {
        List<PaymentCompensationOutbox> pending = outboxRepository.findByStatus(OutboxStatus.PENDING);
        if (pending.isEmpty()) return;

        log.info("[OUTBOX][START] pendingCount={}", pending.size());

        for (PaymentCompensationOutbox outbox : pending) {
            try {
                processOne(outbox);
            } catch (Exception e) {
                outbox.incrementRetry(MAX_RETRY);
                log.warn("[OUTBOX][{}] outboxId={} retryCount={} error={}",
                        outbox.getStatus() == OutboxStatus.FAILED ? "FAILED" : "RETRY",
                        outbox.getId(), outbox.getRetryCount(), e.getMessage());
            }
        }
    }

    private void processOne(PaymentCompensationOutbox outbox) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(
                outbox.getPayload(), new TypeReference<>() {}
        );
        Long bookingId  = ((Number) payload.get("bookingId")).longValue();
        Long concertId  = ((Number) payload.get("concertId")).longValue();

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
}
