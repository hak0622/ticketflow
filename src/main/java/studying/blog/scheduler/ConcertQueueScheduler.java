package studying.blog.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.repository.ConcertRepository;
import studying.blog.service.QueueService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class ConcertQueueScheduler {

    private final QueueService queueService;
    private final ConcertRepository concertRepository;

    private static final long SLOW_SCHEDULER_MS = 100;

    @Scheduled(fixedDelay = 5000)
    public void processQueue() {
        int batchSize = 50;
        int ttlSeconds = 600; // 10분

        long t0 = System.nanoTime();

        List<Concert> openConcerts = concertRepository.findByStatus(ConcertStatus.OPEN);
        if (openConcerts.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        for (Concert concert : openConcerts) {
            Long concertId = concert.getId();

            try {
                // 오픈 전이면 아직 입장권 발급 안 함
                if (concert.getEventAt() != null && now.isBefore(concert.getEventAt())) {
                    continue;
                }

                // 상태가 바뀌었으면 스킵
                if (concert.getStatus() == ConcertStatus.CLOSED || concert.getStatus() == ConcertStatus.SOLD_OUT) {
                    continue;
                }

                long tConcert = System.nanoTime();

                List<String> granted = queueService.popAndGrantAdmitted(concertId, batchSize, ttlSeconds);

                long concertMs = (System.nanoTime() - tConcert) / 1_000_000;

                if (!granted.isEmpty()) {
                    log.info("[SCHEDULER][GRANT] concertId={} grantedCount={} batchSize={} ttlSec={} tookMs={}",
                            concertId, granted.size(), batchSize, ttlSeconds, concertMs);
                } else if (concertMs >= SLOW_SCHEDULER_MS) {
                    log.warn("[SCHEDULER][SLOW] concertId={} grantedCount=0 tookMs={}",
                            concertId, concertMs);
                }

            } catch (Exception e) {
                log.error("[SCHEDULER][ERROR] concertId={} errorType={} msg={}",
                        concertId, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        if (totalMs >= SLOW_SCHEDULER_MS) {
            log.warn("[SCHEDULER][TOTAL] concertCount={} tookMs={}", openConcerts.size(), totalMs);
        }
    }
}
