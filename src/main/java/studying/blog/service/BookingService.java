package studying.blog.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final MeterRegistry meterRegistry;

    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
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

                meterRegistry.counter("booking.attempt", "result", "ALREADY_BOOKED").increment();
                return new BookingResult("ALREADY_BOOKED", concert.getId(), concert.getTitle());
            }

            log.info("[BOOKING][CLAIM_FAIL] userId={} concertId={} reason=no_admitted_or_expired claimMs={}",
                    userId, concertId, claimMs);

            meterRegistry.counter("booking.attempt", "result", "CLAIM_FAIL").increment();
            throw new IllegalStateException("입장 권한이 없습니다. (입장권이 없거나 만료/이미 사용됨)");
        }

        // claim 성공
        log.info("[BOOKING][CLAIM_OK] userId={} concertId={} ttlSec={} claimMs={}",
                userId, concertId, ttl, claimMs);

        boolean seatDecremented = false;
        boolean admittedRestored = false;
        try {
            // (3) 중복 예매 방지 (claimAdmitted 이후 체크, 락 불필요)
            long tExistsStart = System.nanoTime();
            boolean already = bookingRepository.existsByConcertIdAndUserId(concertId, userId);
            long existsMs = (System.nanoTime() - tExistsStart) / 1_000_000;

            if (already) {
                // 좌석 차감 전이므로 restoreSeat 불필요
                queueService.restoreAdmitted(concertId, userId, ttl);
                log.info("[BOOKING][RESULT] userId={} concertId={} status=ALREADY_BOOKED existsMs={}",
                        userId, concertId, existsMs);
                meterRegistry.counter("booking.attempt", "result", "ALREADY_BOOKED").increment();
                return new BookingResult("ALREADY_BOOKED", concertId, "");
            }

            // (4) Redis 좌석 원자적 차감
            long remaining = queueService.decrementSeat(concertId);
            if (remaining == -1) {
                // 좌석 차감 전이므로 restoreSeat 불필요
                queueService.restoreAdmitted(concertId, userId, ttl);
                admittedRestored = true;
                log.info("[BOOKING][RESULT] userId={} concertId={} status=SOLD_OUT", userId, concertId);
                meterRegistry.counter("booking.attempt", "result", "SOLD_OUT").increment();
                throw new IllegalStateException("정원이 마감되었습니다.");
            }
            if (remaining == -2) {
                // 키 누락(일시적): admitted 반환해 재시도 허용
                queueService.restoreAdmitted(concertId, userId, ttl);
                admittedRestored = true;
                log.warn("[BOOKING][SEAT_KEY_MISSING] userId={} concertId={}", userId, concertId);
                throw new IllegalStateException("좌석 정보를 읽을 수 없습니다. 잠시 후 다시 시도해주세요.");
            }
            seatDecremented = true;

            // (5) DB 조회 (락 없음, 상태 확인 및 Booking 생성용)
            long tFindStart = System.nanoTime();
            Concert concert = concertRepository.findById(concertId)
                    .orElseThrow(() -> new IllegalArgumentException("Concert not found :" + concertId));
            long findMs = (System.nanoTime() - tFindStart) / 1_000_000;

            // CLOSED 상태 체크 (SOLD_OUT은 Redis 카운터가 처리)
            if (concert.getStatus() == ConcertStatus.CLOSED) {
                queueService.restoreSeat(concertId);
                seatDecremented = false;
                queueService.restoreAdmitted(concertId, userId, ttl);
                admittedRestored = true;
                log.info("[BOOKING][RESULT] userId={} concertId={} status=CLOSED findMs={}", userId, concertId, findMs);
                meterRegistry.counter("booking.attempt", "result", "CLOSED").increment();
                throw new IllegalStateException("마감된 콘서트입니다.");
            }

            // (6) Booking 저장
            long tSaveStart = System.nanoTime();
            Booking booking = Booking.builder()
                    .concert(concert)
                    .userId(userId)
                    .build();
            bookingRepository.save(booking);
            long saveMs = (System.nanoTime() - tSaveStart) / 1_000_000;

            // (7) 마지막 좌석이면 SOLD_OUT 표기
            if (remaining == 0) {
                concertRepository.markSoldOutById(concertId);
            }

            long totalMs = (System.nanoTime() - t0) / 1_000_000;

            log.info("[BOOKING][RESULT] userId={} concertId={} status=BOOKED bookingId={} remaining={} existsMs={} findMs={} saveMs={} totalMs={}",
                    userId, concertId, booking.getId(), remaining, existsMs, findMs, saveMs, totalMs);

            meterRegistry.counter("booking.attempt", "result", "BOOKED").increment();
            return new BookingResult("BOOKED", concert.getId(), concert.getTitle());

        } catch (DataIntegrityViolationException e) {
            // uk_booking_concert_user 위반: 동시 중복 예매. 좌석 + admitted 복구 후 ALREADY_BOOKED 반환
            if (seatDecremented) queueService.restoreSeat(concertId);
            queueService.restoreAdmitted(concertId, userId, ttl);
            long totalMs = (System.nanoTime() - t0) / 1_000_000;
            log.info("[BOOKING][RESULT] userId={} concertId={} status=ALREADY_BOOKED reason=duplicate_key totalMs={}",
                    userId, concertId, totalMs);
            meterRegistry.counter("booking.attempt", "result", "ALREADY_BOOKED").increment();
            return new BookingResult("ALREADY_BOOKED", concertId, "");

        } catch (RuntimeException e) {
            // 그 외 예상치 못한 예외: 미복구 건만 정리
            if (seatDecremented) queueService.restoreSeat(concertId);
            if (!admittedRestored) queueService.restoreAdmitted(concertId, userId, ttl);
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
                .orElseGet(() -> new BookingResult("NOT_BOOKED", concertId, ""));
    }

    @Transactional(readOnly = true)
    public BookingResponse getMyBookingDetail(Long concertId, Long userId) {
        Booking booking = bookingRepository.findByConcertIdAndUserId(concertId, userId)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역이 없습니다."));
        return BookingResponse.from(booking);
    }
}
