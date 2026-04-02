package studying.blog.experiments.e4.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.experiments.e4.domain.Outbox;
import studying.blog.experiments.e4.domain.OutboxStatus;
import studying.blog.experiments.e4.repository.OutboxRepository;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 전략 B: Outbox 기반 보상 이벤트
 *
 * 결제 실패 시 Outbox 레코드를 같은 DB 트랜잭션에 저장.
 * 앱이 죽어도 Outbox가 남아 있어 재시작 후 스케줄러가 재처리 → 보상 유실 없음.
 *
 * 실험에서는 스케줄러 처리 중 동일한 실패율을 적용하되,
 * 재시도(maxRetry)가 있어 결국 100% 보상 성공을 검증한다.
 */
@Service
@RequiredArgsConstructor
public class CompensationStrategyB {

    private final OutboxRepository outboxRepository;
    private final AtomicInteger processCallCount = new AtomicInteger();

    /** 결제 실패 시 호출 — Outbox 저장을 결제 트랜잭션과 묶는다 */
    @Transactional
    public void saveCompensationOutbox(String eventType, String payload) {
        outboxRepository.save(Outbox.create(eventType, payload));
    }

    /**
     * OutboxScheduler 역할 — PENDING 레코드를 꺼내 보상 로직 실행
     *
     * @param failEveryN N번째 처리마다 실패 시뮬레이션 (0이면 항상 성공)
     * @param maxRetry   최대 재시도 횟수
     * @param logic      실제 보상 비즈니스 로직 (payload 수신)
     * @return 최종 성공 처리 건수
     */
    @Transactional
    public int processPending(int failEveryN, int maxRetry, Runnable logic) {
        List<Outbox> pending = outboxRepository.findByStatus(OutboxStatus.PENDING);
        int successCount = 0;

        for (Outbox outbox : pending) {
            int n = processCallCount.incrementAndGet();
            if (failEveryN > 0 && n % failEveryN == 0) {
                outbox.incrementRetry(maxRetry);   // 실패 → retryCount 증가
            } else {
                logic.run();
                outbox.markPublished();
                successCount++;
            }
        }
        return successCount;
    }

    /** 재시도: PENDING 남은 것을 다시 처리 (스케줄러 재실행 시뮬레이션) */
    @Transactional
    public int retryPending(Runnable logic) {
        List<Outbox> pending = outboxRepository.findByStatus(OutboxStatus.PENDING);
        int successCount = 0;
        for (Outbox outbox : pending) {
            logic.run();
            outbox.markPublished();
            successCount++;
        }
        return successCount;
    }

    public void reset() {
        processCallCount.set(0);
    }
}
