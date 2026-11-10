package studying.blog.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import studying.blog.domain.Concert;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.ConcertStatus;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.service.QueueService;

import java.util.List;

/**
 * 서버 기동 완료 시 누락된 Redis 좌석 카운터만 초기화한다.
 *
 * 기존 키는 보존한다. Redis/DB 전체 정합성을 자동 보정하는 기능은 아니다.
 * 대상: CLOSED 제외한 모든 콘서트 (OPEN, SOLD_OUT).
 * 공식: remaining = max(0, totalSeats - 실제 유효 Booking 수)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatCountInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private final studying.blog.repository.BookingAttemptRepository attemptRepository;
    private final ConcertRepository concertRepository;
    private final BookingRepository bookingRepository;
    private final QueueService queueService;

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        List<Concert> targets = concertRepository.findAll().stream()
                .filter(c -> c.getStatus() != ConcertStatus.CLOSED)
                .toList();

        int count = 0;
        for (Concert concert : targets) {
            // An in-flight Redis hold cannot be reconstructed from committed bookings alone.
            if (attemptRepository.existsByConcertId(concert.getId()) && queueService.getRemainingSeat(concert.getId()) == null) {
                log.error("[SEAT_INIT][BLOCKED] concertId={} existing attempts but missing Redis counter; manual reconciliation required", concert.getId());
                continue;
            }
            // 아직 취소되지 않은 결제 대기는 만료 시간이 지났더라도 수량을 점유한다.
            long activeBookings = bookingRepository.countByConcertIdAndStatusIn(
                    concert.getId(), List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED));
            if (activeBookings > concert.getTotalSeats()) {
                log.error("[SEAT_INIT][OVER_CAPACITY] concertId={} totalSeats={} activeBookings={} fallbackRemaining=0",
                        concert.getId(), concert.getTotalSeats(), activeBookings);
            }
            int remaining = (int) Math.max(0L, (long) concert.getTotalSeats() - activeBookings);
            if (queueService.initSeatCountIfAbsent(concert.getId(), remaining)) {
                count++;
            }
        }

        log.info("[SEAT_INIT] 누락된 콘서트 좌석 카운터 초기화 완료. initialized={} preserved={}",
                count, targets.size() - count);
    }
}
