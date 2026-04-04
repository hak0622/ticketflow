package studying.blog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Booking;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.dto.BookingResult;
import studying.blog.dto.BookingResponse;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final ConcertRepository concertRepository;
    private final BookingRepository bookingRepository;
    private final QueueService queueService;

    @Transactional
    public BookingResult book(Long concertId, Long userId) {

        // (1)전체 처리 시간 측정 시작
        long t0 = System.nanoTime();

        // (2) admitted 원자적 선점 (있으면 삭제 + ttl 반환, 없으면 -1)
        long tClaimStart = System.nanoTime();
        long ttl = queueService.claimAdmitted(concertId, userId);
        long claimMs = (System.nanoTime() - tClaimStart) / 1_000_000;

        if (ttl < 0) {
            // 이미 예매되어 있으면 멱등 성공 처리
            boolean already = bookingRepository.existsByConcertIdAndUserId(concertId, userId);
            if (already) {
                Concert concert = concertRepository.findById(concertId)
                        .orElseThrow(() -> new IllegalArgumentException("Concert not found : " + concertId));

                log.info("[BOOKING][RESULT] userId={} concertId={} status=ALREADY_BOOKED reason=already_in_db claimMs={}",
                        userId, concertId, claimMs);

                return new BookingResult("ALREADY_BOOKED", concert.getId(), concert.getTitle());
            }

            log.info("[BOOKING][CLAIM_FAIL] userId={} concertId={} reason=no_admitted_or_expired claimMs={}",
                    userId, concertId, claimMs);

            throw new IllegalStateException("입장 권한이 없습니다. (입장권이 없거나 만료/이미 사용됨)");
        }

        // claim 성공
        log.info("[BOOKING][CLAIM_OK] userId={} concertId={} ttlSec={} claimMs={}",
                userId, concertId, ttl, claimMs);

        try {
            // (3) DB row lock 구간 시간 측정
            long tLockStart = System.nanoTime();
            Concert concert = concertRepository.findByIdForUpdate(concertId)
                    .orElseThrow(() -> new IllegalArgumentException("Concert not found :" + concertId));
            long lockMs = (System.nanoTime() - tLockStart) / 1_000_000;

            // 상태 체크
            if (concert.getStatus() == ConcertStatus.CLOSED) {
                log.info("[BOOKING][RESULT] userId={} concertId={} status=CLOSED lockMs={}", userId, concertId, lockMs);
                throw new IllegalStateException("마감된 콘서트입니다.");
            }
            if (concert.getStatus() == ConcertStatus.SOLD_OUT) {
                log.info("[BOOKING][RESULT] userId={} concertId={} status=SOLD_OUT lockMs={}", userId, concertId, lockMs);
                throw new IllegalStateException("정원이 마감되었습니다.");
            }

            // (4) 중복 예매 방지(멱등 처리)
            long tExistsStart = System.nanoTime();
            boolean already = bookingRepository.existsByConcertIdAndUserId(concertId, userId);
            long existsMs = (System.nanoTime() - tExistsStart) / 1_000_000;

            if (already) {
                log.info("[BOOKING][RESULT] userId={} concertId={} status=ALREADY_BOOKED lockMs={} existsMs={}",
                        userId, concertId, lockMs, existsMs);

                return new BookingResult("ALREADY_BOOKED", concert.getId(), concert.getTitle());
            }

            // 정원 체크 + 차감
            if (!concert.hasSeat()) {
                concert.markSoldOut();
                log.info("[BOOKING][RESULT] userId={} concertId={} status=SOLD_OUT lockMs={} existsMs={}",
                        userId, concertId, lockMs, existsMs);

                throw new IllegalStateException("정원이 마감되었습니다.");
            }

            concert.increaseBooked();
            if (!concert.hasSeat()) {
                concert.markSoldOut();
            }

            // (5) save 시간 측정
            long tSaveStart = System.nanoTime();
            Booking booking = Booking.builder()
                    .concert(concert)
                    .userId(userId)
                    .build();

            bookingRepository.save(booking);
            long saveMs = (System.nanoTime() - tSaveStart) / 1_000_000;

            long totalMs = (System.nanoTime() - t0) / 1_000_000;

            log.info("[BOOKING][RESULT] userId={} concertId={} status=BOOKED bookingId={} lockMs={} existsMs={} saveMs={} totalMs={}",
                    userId, concertId, booking.getId(), lockMs, existsMs, saveMs, totalMs);

            return new BookingResult("BOOKED", concert.getId(), concert.getTitle());

        } catch (RuntimeException e) {
            // DB처리 실패 시 admitted 복구 -> 재시도 가능하게
            queueService.restoreAdmitted(concertId, userId, ttl);

            long totalMs = (System.nanoTime() - t0) / 1_000_000;

            log.warn("[BOOKING][RESTORE] userId={} concertId={} ttlSec={} errorType={} msg={} totalMs={}",
                    userId, concertId, ttl, e.getClass().getSimpleName(), e.getMessage(), totalMs);

            throw e;
        }
    }

    @Transactional(readOnly = true)
    public BookingResult myBooking(Long concertId, Long userId) {
        return bookingRepository.findByConcertIdAndUserId(concertId, userId)
                .map(b -> new BookingResult("BOOKED", concertId, b.getConcert().getTitle()))
                .orElseGet(() -> new BookingResult("NOT_BOOKED", concertId, null));
    }

    @Transactional(readOnly = true)
    public BookingResponse getMyBookingDetail(Long concertId, Long userId) {
        Booking booking = bookingRepository.findByConcertIdAndUserId(concertId, userId)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역이 없습니다."));
        return BookingResponse.from(booking);
    }
}
