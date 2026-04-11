package studying.blog.experiments.e1;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import studying.blog.experiments.e1.domain.Coupon;
import studying.blog.experiments.e1.repository.CouponIssueRepository;
import studying.blog.experiments.e1.repository.CouponRepository;
import studying.blog.experiments.e1.strategy.CouponStrategyA;
import studying.blog.experiments.e1.strategy.CouponStrategyB;
import studying.blog.experiments.e1.strategy.CouponStrategyC;
import studying.blog.support.RedisTestSupport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 실험: 쿠폰 재고 선점 방식 비교
 * - 조건: 100 스레드 동시 발급, 재고 50개
 * - 측정: 성공 건수, DB 발급 건수, 초과 발급 건수, 소요 시간, TPS
 * - 기준: 초과 발급 0건 + TPS 최고인 방식 채택
 */
@Tag("experiment")
class CouponStockExperimentTest extends RedisTestSupport {

    static final int TOTAL_QTY = 50;
    static final int THREADS   = 100;
    static final int MAX_RETRY = 5; // 전략 C 재시도 상한

    @Autowired CouponStrategyA strategyA;
    @Autowired CouponStrategyB strategyB;
    @Autowired CouponStrategyC strategyC;

    @Autowired CouponRepository      couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;

    Coupon coupon;

    @BeforeEach
    void setUp() {
        coupon = couponRepository.save(
                Coupon.builder().totalQty(TOTAL_QTY).issuedCount(0).build()
        );
    }

    @AfterEach
    void tearDown() {
        couponIssueRepository.deleteByCouponId(coupon.getId());
        couponRepository.deleteById(coupon.getId());
    }

    // ──────────────────────────────────────────
    // 전략 A: Redis DECR
    // ──────────────────────────────────────────
    @Test
    void 전략A_Redis_DECR() throws InterruptedException {
        // Redis 재고 키 초기화
        redisTemplate.opsForValue()
                .set(CouponStrategyA.stockKey(coupon.getId()), String.valueOf(TOTAL_QTY));

        Result result = run(userId ->
                strategyA.issue(coupon.getId(), userId)
        );

        long dbCount = couponIssueRepository.countByCouponId(coupon.getId());
        print("A: Redis DECR", result, dbCount);

        assertThat(dbCount).isLessThanOrEqualTo(TOTAL_QTY);
    }

    // ──────────────────────────────────────────
    // 전략 B: Pessimistic Lock
    // ──────────────────────────────────────────
    @Test
    void 전략B_Pessimistic_Lock() throws InterruptedException {
        Result result = run(userId ->
                strategyB.issue(coupon.getId(), userId)
        );

        Coupon reloaded = couponRepository.findById(coupon.getId()).orElseThrow();
        long dbCount = couponIssueRepository.countByCouponId(coupon.getId());
        print("B: Pessimistic Lock", result, dbCount);

        assertThat(reloaded.getIssuedCount()).isLessThanOrEqualTo(TOTAL_QTY);
        assertThat(dbCount).isEqualTo(reloaded.getIssuedCount());
    }

    // ──────────────────────────────────────────
    // 전략 C: Optimistic Lock (재시도 포함)
    // ──────────────────────────────────────────
    @Test
    void 전략C_Optimistic_Lock() throws InterruptedException {
        Result result = run(userId -> {
            for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
                try {
                    strategyC.issueOnce(coupon.getId(), userId);
                    return; // 성공
                } catch (ObjectOptimisticLockingFailureException ignored) {
                    // 충돌 → 재시도
                }
            }
            throw new IllegalStateException("재시도 초과");
        });

        Coupon reloaded = couponRepository.findById(coupon.getId()).orElseThrow();
        long dbCount = couponIssueRepository.countByCouponId(coupon.getId());
        print("C: Optimistic Lock", result, dbCount);

        assertThat(reloaded.getIssuedCount()).isLessThanOrEqualTo(TOTAL_QTY);
        assertThat(dbCount).isEqualTo(reloaded.getIssuedCount());
    }

    // ──────────────────────────────────────────
    // 공통 실행 헬퍼
    // ──────────────────────────────────────────
    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    record Result(int success, int fail, long elapsedMs) {
        double tps() { return elapsedMs == 0 ? 0 : success * 1000.0 / elapsedMs; }
    }

    @FunctionalInterface
    interface IssueTask { void run(long userId); }

    Result run(IssueTask task) throws InterruptedException {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail    = new AtomicInteger();
        CountDownLatch ready  = new CountDownLatch(THREADS);
        CountDownLatch done   = new CountDownLatch(THREADS);
        ExecutorService pool  = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            final long userId = i + 1L;
            pool.submit(() -> {
                ready.countDown();
                try {
                    ready.await();
                    task.run(userId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        long start = System.currentTimeMillis();
        done.await();
        long elapsed = System.currentTimeMillis() - start;

        pool.shutdown();
        return new Result(success.get(), fail.get(), elapsed);
    }

    void print(String label, Result r, long dbCount) {
        System.out.printf(
            "%n=== E1 결과 [%s] ===%n" +
            "  성공: %d건 | 실패: %d건 | DB 발급: %d건 | 초과발급: %d건%n" +
            "  소요: %dms | TPS: %.1f%n",
            label,
            r.success(), r.fail(), dbCount,
            Math.max(0, dbCount - TOTAL_QTY),
            r.elapsedMs(), r.tps()
        );
    }
}
