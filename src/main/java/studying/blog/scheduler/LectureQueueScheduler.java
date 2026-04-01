package studying.blog.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.repository.LectureRepository;
import studying.blog.service.QueueService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class LectureQueueScheduler {

    private final QueueService queueService;
    private final LectureRepository lectureRepository;

    private static final long SLOW_SCHEDULER_MS = 100;

    @Scheduled(fixedDelay = 5000)
    public void processQueue() {
        int batchSize = 50;
        int ttlSeconds = 600; // 10분

        long t0 = System.nanoTime();

        List<Lecture> openLectures = lectureRepository.findByStatus(LectureStatus.OPEN);
        if (openLectures.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        for (Lecture lecture : openLectures) {
            Long lectureId = lecture.getId();

            try {
                // 오픈 전이면 아직 입장권 발급 안 함
                if (lecture.getOpenAt() != null && now.isBefore(lecture.getOpenAt())) {
                    continue;
                }

                // 상태가 바뀌었으면 스킵
                if (lecture.getStatus() == LectureStatus.CLOSED || lecture.getStatus() == LectureStatus.SOLD_OUT) {
                    continue;
                }

                long tLecture = System.nanoTime();

                List<String> granted = queueService.popAndGrantAdmitted(lectureId, batchSize, ttlSeconds);

                long lectureMs = (System.nanoTime() - tLecture) / 1_000_000;

                if (!granted.isEmpty()) {
                    log.info("[SCHEDULER][GRANT] lectureId={} grantedCount={} batchSize={} ttlSec={} tookMs={}",
                            lectureId, granted.size(), batchSize, ttlSeconds, lectureMs);
                } else if (lectureMs >= SLOW_SCHEDULER_MS) {
                    log.warn("[SCHEDULER][SLOW] lectureId={} grantedCount=0 tookMs={}",
                            lectureId, lectureMs);
                }

            } catch (Exception e) {
                log.error("[SCHEDULER][ERROR] lectureId={} errorType={} msg={}",
                        lectureId, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        if (totalMs >= SLOW_SCHEDULER_MS) {
            log.warn("[SCHEDULER][TOTAL] lectureCount={} tookMs={}", openLectures.size(), totalMs);
        }
    }
}
