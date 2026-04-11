package studying.blog.experiments.e4;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import studying.blog.experiments.e4.repository.OutboxRepository;
import studying.blog.experiments.e4.strategy.CompensationStrategyA;
import studying.blog.experiments.e4.strategy.CompensationStrategyB;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4 실험: 보상 트랜잭션 트리거 방식 비교
 *
 * 시나리오: 결제 실패 10회, 각 실패마다 쿠폰 복구 보상 필요
 * 실패 시뮬레이션: 3번 중 1번(33%) 보상 이벤트 발행 실패
 *
 * 측정: 최종 보상 성공 건수, 소요 시간
 * 기준: 보상 성공률 100% 달성 여부
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("experiment")
class CompensationExperimentTest {

    static final int PAYMENT_FAILURES = 10;
    static final int FAIL_EVERY_N    = 3;   // 3번 중 1번 실패 (33%)
    static final int MAX_RETRY       = 3;

    @Autowired CompensationStrategyA strategyA;
    @Autowired CompensationStrategyB strategyB;
    @Autowired OutboxRepository      outboxRepository;

    @BeforeEach
    void setUp() {
        strategyA.reset();
        strategyB.reset();
    }

    @AfterEach
    void tearDown() {
        outboxRepository.deleteAll();
    }

    // ──────────────────────────────────────────
    // 전략 A: 즉시 보상 발행 (재시도 없음)
    // ──────────────────────────────────────────
    @Test
    void 전략A_즉시_보상_발행() {
        AtomicInteger compensated = new AtomicInteger();
        AtomicInteger lost        = new AtomicInteger();

        long start = System.currentTimeMillis();

        for (int i = 0; i < PAYMENT_FAILURES; i++) {
            // 결제 실패 → catch 블록에서 즉시 보상 시도
            try {
                strategyA.compensate(FAIL_EVERY_N, compensated::incrementAndGet);
            } catch (RuntimeException e) {
                lost.incrementAndGet(); // 보상 유실
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        print("A: 즉시 보상 발행", compensated.get(), lost.get(), elapsed);

        // 보상 성공률 100% 달성 불가 → 기각 조건
        assertThat(compensated.get()).isLessThan(PAYMENT_FAILURES);
        assertThat(lost.get()).isGreaterThan(0);
    }

    // ──────────────────────────────────────────
    // 전략 B: Outbox 기반 보상 (재시도 포함)
    // ──────────────────────────────────────────
    @Test
    void 전략B_Outbox_기반_보상() {
        AtomicInteger compensated = new AtomicInteger();

        long start = System.currentTimeMillis();

        // Step 1: 결제 실패 10회 → 각각 Outbox 저장 (원자적)
        for (int i = 0; i < PAYMENT_FAILURES; i++) {
            strategyB.saveCompensationOutbox(
                    "coupon.cancel",
                    "{\"couponIssueId\":" + (i + 1) + "}"
            );
        }

        // Step 2: 스케줄러 1차 실행 (33% 실패율 적용)
        int firstRun = strategyB.processPending(
                FAIL_EVERY_N, MAX_RETRY, compensated::incrementAndGet
        );

        // Step 3: 실패한 것 재시도 (스케줄러 재실행 시뮬레이션)
        int retryRun = strategyB.retryPending(compensated::incrementAndGet);

        long elapsed = System.currentTimeMillis() - start;
        int lost = PAYMENT_FAILURES - compensated.get();

        print("B: Outbox 기반 보상 (1차=" + firstRun + "건, 재시도=" + retryRun + "건)",
                compensated.get(), lost, elapsed);

        // 보상 성공률 100% 달성 → 채택 조건
        assertThat(compensated.get()).isEqualTo(PAYMENT_FAILURES);
        assertThat(lost).isEqualTo(0);
    }

    void print(String label, int success, int lost, long elapsed) {
        double rate = (double) success / PAYMENT_FAILURES * 100;
        System.out.printf(
            "%n=== E4 결과 [%s] ===%n" +
            "  보상 성공: %d/%d건 (%.0f%%) | 유실: %d건 | 소요: %dms%n",
            label, success, PAYMENT_FAILURES, rate, lost, elapsed
        );
    }
}
