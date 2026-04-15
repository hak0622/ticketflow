package studying.blog.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.repository.ConcertRepository;
import studying.blog.service.QueueService;

import java.util.List;

/**
 * 서버 기동 완료 시 전체 콘서트의 Redis 좌석 카운터를 재초기화한다.
 *
 * 목적: Redis 장애 복구, 서버 재기동 후 seats:concert:{id} 키 누락 방지.
 * 대상: CLOSED 제외한 모든 콘서트 (OPEN, SOLD_OUT).
 * 공식: remaining = max(0, totalSeats - bookedCount)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatCountInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private final ConcertRepository concertRepository;
    private final QueueService queueService;

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        List<Concert> targets = concertRepository.findAll().stream()
                .filter(c -> c.getStatus() != ConcertStatus.CLOSED)
                .toList();

        int count = 0;
        for (Concert concert : targets) {
            int remaining = Math.max(0, concert.getTotalSeats() - concert.getBookedCount());
            queueService.initSeatCount(concert.getId(), remaining);
            count++;
        }

        log.info("[SEAT_INIT] 콘서트 좌석 카운터 초기화 완료. count={}", count);
    }
}
