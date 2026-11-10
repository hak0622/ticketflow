package studying.blog.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import studying.blog.domain.BookingStatus;
import studying.blog.repository.BookingRepository;
import studying.blog.service.BookingSeatReleaseService;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryScheduler {
    private final BookingRepository bookingRepository;
    private final BookingSeatReleaseService release;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "BookingExpiryScheduler", lockAtMostFor = "55s", lockAtLeastFor = "0s")
    public void expireStaleBookings() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);
        for (var booking : bookingRepository.findAllByStatusAndCreatedAtBefore(BookingStatus.PENDING_PAYMENT, threshold)) {
            try {
                release.cancelAndRelease(booking.getId(), threshold);
            } catch (RuntimeException error) {
                log.warn("[EXPIRY][RETRY] bookingId={} error={}", booking.getId(), error.toString());
            }
        }
    }
}
