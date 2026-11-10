package studying.blog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingSeatReleaseService {
    private final BookingAttemptTransactions transactions;

    public void cancelAndRelease(Long bookingId, LocalDateTime expiryThreshold) {
        String id = transactions.cancel(bookingId, expiryThreshold);
        if (id != null) transactions.release(id);
    }
    public void compensate(Long bookingId,Long paymentId) {
        String id=transactions.cancel(bookingId,null,paymentId);
        if(id!=null) transactions.release(id);
    }
    public void releaseSafely(String id) {
        try {
            transactions.release(id);
        } catch (RuntimeException failure) {
            retrySafely(id, failure);
        }
    }
    public void recover(String id) {
        try {
            if (transactions.prepareRecovery(id)) transactions.release(id);
        } catch (RuntimeException failure) {
            retrySafely(id, failure);
        }
    }
    private void retrySafely(String id, RuntimeException failure) {
        log.warn("[BOOKING_RECOVERY][PENDING] requestId={} error={}", id, failure.toString());
        try {
            transactions.recordRetry(id, failure);
        } catch (RuntimeException unavailable) {
            log.error("[BOOKING_RECOVERY][DB_UNAVAILABLE] requestId={}; durable state retained", id, unavailable);
        }
    }
}
