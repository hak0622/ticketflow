package studying.blog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.annotation.Transactional;

import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.repository.ConcertRepository;
import studying.blog.support.RedisTestSupport;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@Tag("integration")
class QueueServiceIntegrationTest extends RedisTestSupport {

    @Autowired
    private QueueService queueService;

    @Autowired
    private ConcertRepository concertRepository;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private Concert concert;

    @BeforeEach
    void setUp() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();

        concert = Concert.builder()
                .title("테스트 콘서트")
                .totalSeats(30)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .eventAt(LocalDateTime.now().plusDays(1))
                .build();
        concertRepository.save(concert);
    }

    @Test
    void enqueue_순서대로_등록되면_순번이_반환된다() {
        Long pos1 = queueService.enqueue(concert.getId(), 1L);
        Long pos2 = queueService.enqueue(concert.getId(), 2L);
        Long pos3 = queueService.enqueue(concert.getId(), 3L);

        assertThat(pos1).isEqualTo(1L);
        assertThat(pos2).isEqualTo(2L);
        assertThat(pos3).isEqualTo(3L);
    }

    @Test
    void getPosition_enqueue_후_순번이_조회된다() {
        queueService.enqueue(concert.getId(), 10L);
        queueService.enqueue(concert.getId(), 20L);

        assertThat(queueService.getPosition(concert.getId(), 10L)).isEqualTo(1L);
        assertThat(queueService.getPosition(concert.getId(), 20L)).isEqualTo(2L);
    }

    @Test
    void popAndGrantAdmitted_후_claimAdmitted_성공한다() {
        queueService.enqueue(concert.getId(), 1L);
        queueService.enqueue(concert.getId(), 2L);

        List<String> granted = queueService.popAndGrantAdmitted(concert.getId(), 2, 600);
        assertThat(granted).hasSize(2);

        long ttl = queueService.claimAdmitted(concert.getId(), 1L);
        assertThat(ttl).isGreaterThan(0);
    }

    @Test
    void claimAdmitted_두번_호출하면_두번째는_마이너스1_반환() {
        queueService.enqueue(concert.getId(), 1L);
        queueService.popAndGrantAdmitted(concert.getId(), 1, 600);

        long first = queueService.claimAdmitted(concert.getId(), 1L);
        long second = queueService.claimAdmitted(concert.getId(), 1L);

        assertThat(first).isGreaterThan(0);
        assertThat(second).isEqualTo(-1L);
    }

    @Test
    void restoreAdmitted_후_claimAdmitted_재소비_가능() {
        queueService.enqueue(concert.getId(), 1L);
        queueService.popAndGrantAdmitted(concert.getId(), 1, 600);

        long ttl = queueService.claimAdmitted(concert.getId(), 1L);   // 소비
        queueService.restoreAdmitted(concert.getId(), 1L, ttl);        // 복구

        long reClaimed = queueService.claimAdmitted(concert.getId(), 1L);  // 재소비
        assertThat(reClaimed).isGreaterThan(0);
    }

    @Test
    void 동일_Outbox로_좌석을_여러번_복구해도_한번만_증가한다() {
        queueService.initSeatCount(concert.getId(), 0);

        boolean first = queueService.restoreSeatOnceForCompensation(concert.getId(), 101L);
        boolean second = queueService.restoreSeatOnceForCompensation(concert.getId(), 101L);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(1L);
    }

    @Test
    void 동일_Outbox_동시_복구에서도_좌석은_한번만_증가한다() throws Exception {
        queueService.initSeatCount(concert.getId(), 0);
        int requestCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return queueService.restoreSeatOnceForCompensation(concert.getId(), 202L);
                }));
            }
            ready.await();
            start.countDown();

            long restoredCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) restoredCount++;
            }

            assertThat(restoredCount).isEqualTo(1L);
            assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 좌석_키가_없으면_Outbox_처리기록을_남기지_않아_초기화_후_재시도할_수_있다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> queueService.restoreSeatOnceForCompensation(concert.getId(), 303L))
                .isInstanceOf(IllegalStateException.class);

        queueService.initSeatCount(concert.getId(), 0);

        assertThat(queueService.restoreSeatOnceForCompensation(concert.getId(), 303L)).isTrue();
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(1L);
    }
}
