package studying.blog.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import studying.blog.domain.Coupon;
import studying.blog.repository.CouponIssueRepository;
import studying.blog.repository.CouponRepository;
import studying.blog.support.RedisTestSupport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CouponIssueConcurrencyTest extends RedisTestSupport {

    static final int TOTAL_QTY = 50;
    static final int THREADS   = 100;

    @Autowired private CouponService couponService;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponIssueRepository couponIssueRepository;

    private Coupon coupon;

    @BeforeEach
    void setUp() {
        coupon = couponRepository.save(Coupon.builder()
                .code("CONCURRENCY_TEST")
                .discountAmount(1000)
                .totalQty(TOTAL_QTY)
                .build());
        couponService.initStock(coupon.getId(), TOTAL_QTY);
    }

    @AfterEach
    void tearDown() {
        couponIssueRepository.deleteAll();
        couponRepository.deleteById(coupon.getId());
    }

    @Test
    void 동시_100명_발급_요청_재고_50개_정확히_50건만_발급() throws InterruptedException {
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
                    couponService.issue("CONCURRENCY_TEST", userId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();
        pool.shutdown();

        long issued = couponIssueRepository.count();
        System.out.printf("%n=== 동시성 결과 ===%n성공: %d | 실패: %d | DB 발급: %d건%n",
                success.get(), fail.get(), issued);

        assertThat(issued).isEqualTo(TOTAL_QTY);
        assertThat(success.get()).isEqualTo(TOTAL_QTY);
    }
}
