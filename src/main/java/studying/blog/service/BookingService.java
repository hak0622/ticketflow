package studying.blog.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingAttempt;
import studying.blog.domain.BookingAttemptStatus;
import studying.blog.dto.BookingResult;
import studying.blog.dto.BookingResponse;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.BookingAttemptRepository;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {
    private final BookingAttemptTransactions transactions;
    private final BookingSeatReleaseService releaseService;
    private final BookingAttemptRepository attempts;
    private final BookingRepository bookingRepository;
    private final MeterRegistry meterRegistry;

    public BookingResult book(Long concertId, Long userId) {
        return book(concertId, userId, UUID.randomUUID().toString());
    }

    // Deliberately no outer transaction: REQUESTED must commit before Redis is touched.
    public BookingResult book(Long concertId, Long userId, String requestId) {
        requestId = UUID.fromString(requestId).toString();
        long start = System.nanoTime();
        try {
            transactions.create(requestId, concertId, userId);
        } catch (DataIntegrityViolationException duplicate) {
            if (!attempts.existsById(requestId)) throw duplicate;
        }
        BookingResult result;
        try {
            result = transactions.execute(requestId, concertId, userId);
        } catch (BookingAttemptTransactions.RequestOwnershipException invalidOwner) {
            throw invalidOwner;
        } catch (RuntimeException failure) {
            BookingResult resolved;
            try {
                // Fresh locked primary read waits for the original transaction's outcome.
                resolved = transactions.prepareFailed(requestId, concertId, userId, failure.getMessage());
            } catch (RuntimeException unknown) {
                log.error("[BOOKING][OUTCOME_UNKNOWN] requestId={}; no Redis release", requestId, unknown);
                return new BookingResult("BOOKING_PROCESSING", concertId, "");
            }
            if ("BOOKING_PROCESSING".equals(resolved.getStatus()) || "BOOKED".equals(resolved.getStatus()) || "ALREADY_BOOKED".equals(resolved.getStatus())
                    || "SOLD_OUT".equals(resolved.getStatus())) {
                result = resolved;
            } else {
                releaseService.releaseSafely(requestId);
                try {
                    if ("BOOKING_PROCESSING".equals(attemptStatus(concertId, userId, requestId).get("status")))
                        return new BookingResult("BOOKING_PROCESSING", concertId, "");
                } catch (RuntimeException unavailable) {
                    return new BookingResult("BOOKING_PROCESSING", concertId, "");
                }
                throw failure;
            }
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= 30) log.warn("[BOOKING][SLOW] requestId={} totalMs={}", requestId, ms);
        }
        meterRegistry.counter("booking.attempt", "result", result.getStatus()).increment();
        // This business rejection is raised only AFTER the transaction has committed.
        if ("SOLD_OUT".equals(result.getStatus())) throw new IllegalStateException("정원이 마감되었습니다.");
        return result;
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> attemptStatus(Long concertId, Long userId, String requestId) {
        BookingAttempt a = attempts.findById(UUID.fromString(requestId).toString())
                .filter(x -> x.getConcertId().equals(concertId) && x.getUserId().equals(userId))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        var data = new java.util.HashMap<String, Object>();
        String state = a.getStatus() == BookingAttemptStatus.REQUESTED || a.getStatus() == BookingAttemptStatus.RELEASE_PENDING
                ? "BOOKING_PROCESSING" : a.getResultStatus();
        data.put("requestId", a.getId()); data.put("concertId", concertId);
        data.put("status", state); data.put("reason", state.equals("BOOKING_PROCESSING") ? "RECOVERING" : a.getStatus().name());
        if (state.equals("BOOKING_PROCESSING")) data.put("retryAfterMs", 5000);
        Booking b = a.getBookingId() == null ? null : bookingRepository.findById(a.getBookingId()).orElseThrow();
        if (b == null && state.equals("ALREADY_BOOKED")) b = bookingRepository.findByConcertIdAndUserId(concertId,userId).orElse(null);
        if (b != null) { data.put("bookingId", b.getId()); data.put("bookingStatus", b.getStatus().name()); }
        return data;
    }

    @Transactional(readOnly = true)
    public BookingResult myBooking(Long concertId, Long userId) {
        return bookingRepository.findByConcertIdAndUserId(concertId, userId)
                .map(b -> new BookingResult("BOOKED", concertId, b.getConcert().getTitle()))
                .orElseGet(() -> new BookingResult("NOT_BOOKED", concertId, ""));
    }

    @Transactional(readOnly = true)
    public BookingResponse getMyBookingDetail(Long concertId, Long userId) {
        Booking booking = bookingRepository.findByConcertIdAndUserId(concertId, userId)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역이 없습니다."));
        return BookingResponse.from(booking);
    }
}
