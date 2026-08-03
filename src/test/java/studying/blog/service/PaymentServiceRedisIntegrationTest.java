package studying.blog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;
import studying.blog.domain.Booking;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.repository.PaymentCompensationOutboxRepository;
import studying.blog.repository.PaymentRepository;
import studying.blog.scheduler.BookingExpiryScheduler;
import studying.blog.scheduler.ConcertQueueScheduler;
import studying.blog.scheduler.PaymentCompensationScheduler;
import studying.blog.support.RedisTestSupport;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentServiceRedisIntegrationTest extends RedisTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentCompensationOutboxRepository outboxRepository;
    @Autowired private RedisConnectionFactory redisConnectionFactory;

    @MockBean private BookingExpiryScheduler bookingExpiryScheduler;
    @MockBean private ConcertQueueScheduler concertQueueScheduler;
    @MockBean private PaymentCompensationScheduler paymentCompensationScheduler;

    private Concert concert;
    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
        bookingRepository.deleteAll();
        concertRepository.deleteAll();
        redisConnectionFactory.getConnection().serverCommands().flushDb();

        concert = concertRepository.save(Concert.builder()
                .title("Redis 멱등성 테스트 콘서트")
                .totalSeats(10)
                .bookedCount(1)
                .status(ConcertStatus.OPEN)
                .eventAt(LocalDateTime.now().minusMinutes(1))
                .price(90000)
                .build());

        bookingRepository.save(Booking.builder()
                .concert(concert)
                .userId(USER_ID)
                .build());

        ReflectionTestUtils.setField(paymentService, "failRate", 0);
    }

    @Test
    void 실제_Redis_SETNX로_동일_idempotencyKey_동시요청시_Payment는_1건만_생성() throws InterruptedException {
        int threadCount = 20;
        String iKey = UUID.randomUUID().toString();
        AtomicInteger completedOrCachedCount = new AtomicInteger();
        AtomicInteger processingCount = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    paymentService.pay(concert.getId(), USER_ID, paymentRequest(iKey));
                    completedOrCachedCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    if (e.getMessage().contains("결제가 처리 중입니다")) {
                        processingCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 예상 밖 예외는 아래 전체 카운트 검증에서 드러난다.
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(completedOrCachedCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(processingCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(completedOrCachedCount.get() + processingCount.get()).isEqualTo(threadCount);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    private studying.blog.dto.PaymentRequest paymentRequest(String idempotencyKey) {
        studying.blog.dto.PaymentRequest request = new studying.blog.dto.PaymentRequest();
        ReflectionTestUtils.setField(request, "idempotencyKey", idempotencyKey);
        ReflectionTestUtils.setField(request, "paymentMethod", "CARD");
        return request;
    }
}
