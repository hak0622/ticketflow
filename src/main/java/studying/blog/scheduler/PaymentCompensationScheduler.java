package studying.blog.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import studying.blog.domain.OutboxStatus;
import studying.blog.domain.PaymentCompensationOutbox;
import studying.blog.repository.PaymentCompensationOutboxRepository;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompensationScheduler {

    private final PaymentCompensationOutboxRepository outboxRepository;
    private final PaymentCompensationOutboxProcessor outboxProcessor;
    private final MeterRegistry meterRegistry;

    // processPending은 트랜잭션 없음 — 각 건은 outboxProcessor가 REQUIRES_NEW로 처리
    @Scheduled(fixedDelay = 10_000)
    @SchedulerLock(name = "PaymentCompensationScheduler", lockAtMostFor = "9s", lockAtLeastFor = "0s")
    public void processPending() {
        List<PaymentCompensationOutbox> pending = outboxRepository.findByStatus(OutboxStatus.PENDING);
        if (pending.isEmpty()) return;

        log.info("[OUTBOX][START] pendingCount={}", pending.size());

        for (PaymentCompensationOutbox outbox : pending) {
            Long outboxId = outbox.getId();
            try {
                outboxProcessor.process(outboxId);
                meterRegistry.counter("scheduler.payment_compensation.processed", "result", "PUBLISHED").increment();
            } catch (Exception e) {
                outboxProcessor.markRetry(outboxId, e);
                meterRegistry.counter("scheduler.payment_compensation.processed", "result", "FAILED").increment();
            }
        }
    }
}
