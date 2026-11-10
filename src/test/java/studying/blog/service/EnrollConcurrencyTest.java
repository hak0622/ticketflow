package studying.blog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
public class EnrollConcurrencyTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ConcertRepository concertRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private QueueService queueService;

    @Autowired private studying.blog.repository.BookingAttemptRepository attemptRepository;

    private Concert concert;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        bookingRepository.deleteAll();
        concertRepository.deleteAll();

        concert = Concert.builder()
                .title("동시성 테스트 콘서트")
                .totalSeats(1)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .eventAt(LocalDateTime.now().minusMinutes(1))
                .build();

        concertRepository.save(concert);

        // Redis 좌석 카운터 초기화 (totalSeats=1)
        queueService.initSeatCount(concert.getId(), concert.getTotalSeats());

        // 10개 userId 모두 입장권 발급 (각 스레드가 admitted 체크를 통과하도록)
        for (int i = 0; i < 10; i++) {
            queueService.restoreAdmitted(concert.getId(), (long) i, 600L);
        }
    }

    @Test
    void 동시에_여러명이_신청해도_1명만_성공한다() throws Exception{
        int threadCount = 10;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // admitted 토큰은 @BeforeEach에서 발급 완료 — mock 불필요
        for(int i=0; i<threadCount; i++){
            final Long userId = (long) i;

            executorService.submit(()->{
                try{
                    bookingService.book(concert.getId(), userId);
                }catch (Exception e){

                }finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        executorService.shutdownNow();

        Concert reloaded = concertRepository.findById(concert.getId()).orElseThrow();

        // Redis owns capacity; the old DB bookedCount remains unchanged.
        assertThat(reloaded.getBookedCount()).isZero();
        assertThat(queueService.getRemainingSeat(concert.getId())).isZero();

        // Booking도 1개만 저장되어야 한다.
        assertThat(bookingRepository.count()).isEqualTo(1);
    }
}
