package studying.blog.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.service.QueueService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryScheduler {

    private final BookingRepository bookingRepository;
    private final ConcertRepository concertRepository;
    private final QueueService queueService;

    private static final int EXPIRY_MINUTES = 30;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "BookingExpiryScheduler", lockAtMostFor = "55s", lockAtLeastFor = "0s")
    @Transactional
    public void expireStaleBookings() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(EXPIRY_MINUTES);

        List<Booking> expired = bookingRepository.findAllByStatusAndCreatedAtBefore(
                BookingStatus.PENDING_PAYMENT, threshold
        );
        if (expired.isEmpty()) return;

        log.info("[EXPIRY][START] expiredCount={} threshold={}", expired.size(), threshold);

        // 콘서트 단위로 묶어서 락을 콘서트당 1회만 획득
        Map<Long, List<Booking>> byConcertId = expired.stream()
                .collect(Collectors.groupingBy(b -> b.getConcert().getId()));

        for (Map.Entry<Long, List<Booking>> entry : byConcertId.entrySet()) {
            Long concertId = entry.getKey();
            List<Booking> bookings = entry.getValue();

            bookings.forEach(Booking::cancel);

            for (int i = 0; i < bookings.size(); i++) {
                queueService.restoreSeat(concertId);
            }
            concertRepository.reopenIfSoldOutById(concertId);

            log.info("[EXPIRY][DONE] concertId={} cancelledCount={} reopenedIfSoldOut=true",
                    concertId, bookings.size());
        }
    }
}
