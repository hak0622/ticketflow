package studying.blog.experiments.e3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import studying.blog.experiments.e3.repository.IdempotencyKeyRepository;
import studying.blog.experiments.e3.repository.ProcessedEventRepository;
import studying.blog.experiments.e3.strategy.IdempotencyStrategyA;
import studying.blog.experiments.e3.strategy.IdempotencyStrategyB;
import studying.blog.experiments.e3.strategy.IdempotencyStrategyC;
import studying.blog.support.RedisTestSupport;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3 실험: Kafka Consumer 멱등성 처리 방식 비교
 * - 시나리오: 동일 eventId를 10 스레드가 동시에 처리 시도 (at-least-once 시뮬레이션)
 * - 측정: 실제 처리 건수 (정상=1), 중복 처리 건수, 소요 시간
 * - 기준: 중복 처리 0건 + 구현 단순
 */
@Tag("experiment")
class IdempotencyExperimentTest extends RedisTestSupport {

    static final int THREADS = 10;

    @Autowired IdempotencyStrategyA strategyA;
    @Autowired IdempotencyStrategyB strategyB;
    @Autowired IdempotencyStrategyC strategyC;

    @Autowired IdempotencyKeyRepository  idempotencyKeyRepository;
    @Autowired ProcessedEventRepository  processedEventRepository;
    @Autowired StringRedisTemplate       redisTemplate;

    String eventId;

    @AfterEach
    void tearDown() {
        if (eventId != null) {
            idempotencyKeyRepository.deleteById(eventId);
            processedEventRepository.deleteByEventId(eventId);
            redisTemplate.delete("idempotency:" + eventId);
        }
    }

    @Test
    void 전략A_DB_PK_INSERT() throws InterruptedException {
        eventId = UUID.randomUUID().toString();
        Result r = run(eventId, id -> strategyA.tryAcquire(id));

        assertThat(r.processCount()).isEqualTo(1);
        print("A: DB PK INSERT", r);
    }

    @Test
    void 전략B_SELECT_후_INSERT() throws InterruptedException {
        eventId = UUID.randomUUID().toString();
        Result r = run(eventId, id -> strategyB.tryAcquire(id));

        assertThat(r.processCount()).isEqualTo(1);
        print("B: SELECT→INSERT", r);
    }

    @Test
    void 전략C_Redis_SETNX() throws InterruptedException {
        eventId = UUID.randomUUID().toString();
        Result r = run(eventId, id -> strategyC.tryAcquire(id));

        assertThat(r.processCount()).isEqualTo(1);
        print("C: Redis SETNX", r);
    }

    // ──────────────────────────────────────────
    // 공통 실행 헬퍼
    // ──────────────────────────────────────────

    record Result(int processCount, int skipCount, long elapsedMs) {}

    @FunctionalInterface
    interface AcquireTask { boolean tryAcquire(String eventId); }

    Result run(String id, AcquireTask task) throws InterruptedException {
        AtomicInteger processCount = new AtomicInteger();
        AtomicInteger skipCount    = new AtomicInteger();
        CountDownLatch ready       = new CountDownLatch(THREADS);
        CountDownLatch done        = new CountDownLatch(THREADS);
        ExecutorService pool       = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    ready.await();
                    boolean acquired = task.tryAcquire(id);
                    if (acquired) processCount.incrementAndGet();
                    else          skipCount.incrementAndGet();
                } catch (Exception e) {
                    skipCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        long start = System.currentTimeMillis();
        done.await();
        long elapsed = System.currentTimeMillis() - start;

        pool.shutdown();
        return new Result(processCount.get(), skipCount.get(), elapsed);
    }

    void print(String label, Result r) {
        System.out.printf(
            "%n=== E3 결과 [%s] ===%n" +
            "  처리: %d건 | 중복 스킵: %d건 | 중복 처리: %d건 | 소요: %dms%n",
            label,
            r.processCount(), r.skipCount(),
            Math.max(0, r.processCount() - 1),
            r.elapsedMs()
        );
    }
}
