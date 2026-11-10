package studying.blog.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import studying.blog.domain.*;
import studying.blog.dto.BookingResult;
import studying.blog.repository.*;
import java.time.LocalDateTime;
import java.util.Objects;

/** Every public operation completes a separate primary DB transaction before returning. */
@Slf4j
@Service
public class BookingAttemptTransactions {
    private final BookingAttemptRepository attempts;
    private final BookingRepository bookings;
    private final ConcertRepository concerts;
    private final BookingSeatStore seats;
    private final QueueService queue;
    private final TransactionTemplate tx;
    private final PaymentRepository payments;

    public BookingAttemptTransactions(BookingAttemptRepository attempts, BookingRepository bookings,
            ConcertRepository concerts, BookingSeatStore seats, QueueService queue, PaymentRepository payments, PlatformTransactionManager manager) {
        this.payments = payments;
        this.attempts = attempts;
        this.bookings = bookings;
        this.concerts = concerts;
        this.seats = seats;
        this.queue = queue;
        this.tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    public void create(String id, Long concertId, Long userId) {
        tx.executeWithoutResult(s -> {
            // Assigned UUID + JpaRepository.save would merge and overwrite a replay's state.
            attempts.saveAndFlush(new BookingAttempt(id, concertId, userId));
        });
    }

    private BookingAttempt locked(String id) {
        return attempts.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("Unknown booking request"));
    }
    private void validate(BookingAttempt a, Long concertId, Long userId) {
        if (!Objects.equals(a.getConcertId(), concertId) || !Objects.equals(a.getUserId(), userId))
            throw new RequestOwnershipException();
    }
    public static class RequestOwnershipException extends IllegalArgumentException {
        public RequestOwnershipException() { super("Idempotency-Key belongs to a different booking request"); }
    }
    private BookingResult result(BookingAttempt a) {
        return new BookingResult(a.getResultStatus(), a.getConcertId(), a.getConcertTitle());
    }

    public BookingResult execute(String id, Long concertId, Long userId) {
        return tx.execute(s -> {
            BookingAttempt a = locked(id);
            validate(a, concertId, userId);
            if (a.getStatus() != BookingAttemptStatus.REQUESTED) return result(a);
            var existing = bookings.findBookedConcertTitle(concertId, userId);
            if (existing.isPresent()) {
                a.alreadyBooked(existing.get());
                return result(a);
            }
            Concert concert = concerts.findById(concertId)
                    .orElseThrow(() -> new IllegalArgumentException("Concert not found: " + concertId));
            if (concert.getStatus() == ConcertStatus.CLOSED) throw new IllegalStateException("마감된 콘서트입니다.");
            long remaining = seats.reserve(a);
            if (remaining == -1) {
                a.soldOut();
                return result(a);
            }
            if (remaining == -3) throw new IllegalStateException("입장 권한이 없습니다. (입장권이 없거나 만료/이미 사용됨)");
            if (remaining < 0) throw new IllegalStateException("종료되었거나 다른 예매 요청이 처리 중입니다.");
            Booking booking = bookings.saveAndFlush(Booking.create(concert, userId));
            a.booked(booking.getId(), concert.getTitle());
            // No concert row lock or bookedCount update. Redis is the quantity authority.
            if (remaining == 0) concert.markSoldOut();
            attempts.flush();
            return result(a);
        });
    }

    /** Resolve even an ambiguous commit by a fresh locking read on the primary DB.
     * If the read/lock/commit fails, callers MUST leave Redis untouched. */
    public BookingResult prepareFailed(String id, Long concertId, Long userId, String reason) {
        return tx.execute(s -> {
            BookingAttempt a = locked(id);
            validate(a, concertId, userId);
            if (restoreSoldOut(a)) return result(a);
            if (a.getStatus() == BookingAttemptStatus.REQUESTED) a.releasePending(reason);
            return result(a);
        });
    }

    public boolean prepareRecovery(String id) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            BookingAttempt a = locked(id);
            if (a.getStatus() == BookingAttemptStatus.REQUESTED && a.getNextRetryAt().isAfter(LocalDateTime.now()))
                return false;
            if (restoreSoldOut(a)) return false;
            if (a.getStatus() == BookingAttemptStatus.REQUESTED)
                a.releasePending("Unfinished request recovered");
            return a.getStatus() == BookingAttemptStatus.RELEASE_PENDING;
        }));
    }

    /** Caller holds the request row lock. Never replace a booking's durable outcome. */
    private boolean restoreSoldOut(BookingAttempt a) {
        if (a.getBookingId() == null
                && (a.getStatus() == BookingAttemptStatus.REQUESTED || a.getStatus() == BookingAttemptStatus.RELEASE_PENDING)
                && seats.isSoldOut(a)) {
            a.soldOut();
            return true;
        }
        return false;
    }

    /** Lock order for existing bookings: Booking -> Attempt; payment also locks Booking. */
    public String cancel(Long bookingId, LocalDateTime expiryThreshold) { return cancel(bookingId,expiryThreshold,null); }
    public String cancel(Long bookingId, LocalDateTime expiryThreshold, Long sourcePaymentId) {
        return tx.execute(s -> {
            Booking b = bookings.findLockedById(bookingId)
                    .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
            if (b.getStatus() == BookingStatus.CONFIRMED) return null;
            var paymentHistory=payments.findAllByBookingIdOrderByIdAsc(bookingId);
            if(paymentHistory.stream().anyMatch(p -> p.unresolved() || p.getStatus()==PaymentStatus.COMPLETED))
                throw new studying.blog.exception.PaymentDeferredException();
            if(sourcePaymentId!=null) {
                var source=paymentHistory.stream().filter(p -> p.getId().equals(sourcePaymentId)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Compensation payment does not belong to Booking"));
                if(source.getStatus()!=PaymentStatus.FAILED && source.getStatus()!=PaymentStatus.CANCELED)
                    throw new studying.blog.exception.PaymentDeferredException();
            }
            if (expiryThreshold != null && !b.getCreatedAt().isBefore(expiryThreshold)) return null;
            BookingAttempt linked = attempts.findByBookingId(bookingId)
                    .orElseThrow(() -> new IllegalStateException("Booking attempt missing; migrate existing booking: " + bookingId));
            BookingAttempt a = locked(linked.getId());
            b.cancel();
            if (a.getStatus() == BookingAttemptStatus.BOOKED) a.releasePending("Booking cancelled");
            if (a.getStatus() != BookingAttemptStatus.RELEASE_PENDING && a.getStatus() != BookingAttemptStatus.RELEASED)
                throw new IllegalStateException("Invalid cancellation attempt state");
            return a.getId();
        });
    }

    /** RELEASE_PENDING was committed before this transaction, including after ambiguous commit. */
    public void release(String id) {
        tx.executeWithoutResult(s -> {
            BookingAttempt a = locked(id);
            if (a.getStatus() != BookingAttemptStatus.RELEASE_PENDING) return;
            if (restoreSoldOut(a)) return;
            BookingSeatStore.ReleaseResult released = seats.release(a);
            if (released == BookingSeatStore.ReleaseResult.NOT_HELD) {
                a.released();
                return;
            }
            // Both flag/cache work are retried if DB commit or Redis response is lost.
            Long remaining = queue.getRemainingSeat(a.getConcertId());
            if (remaining == null) throw new IllegalStateException("Seat key missing; reconciliation required");
            if (remaining > 0) concerts.reopenIfSoldOutById(a.getConcertId());
            queue.deleteConcertStatus(a.getConcertId());
            a.released();
        });
    }

    public void recordRetry(String id, Throwable error) {
        tx.executeWithoutResult(s -> {
            BookingAttempt a = locked(id);
            if (a.getStatus() == BookingAttemptStatus.RELEASE_PENDING || a.getStatus() == BookingAttemptStatus.REQUESTED) {
                a.retry(error);
                if (a.getRetryCount() >= 3) log.error("[BOOKING_RECOVERY][RETRY] requestId={} retryCount={} error={}",
                        id, a.getRetryCount(), a.getLastError());
            }
        });
    }
}
