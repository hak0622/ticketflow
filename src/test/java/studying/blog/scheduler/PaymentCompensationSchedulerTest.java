package studying.blog.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import studying.blog.domain.*;
import studying.blog.repository.*;
import studying.blog.service.QueueService;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class PaymentCompensationSchedulerTest {

    @Autowired private PaymentCompensationScheduler scheduler;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingAttemptRepository attemptRepository;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired private PaymentCompensationOutboxRepository outboxRepository;
    @Autowired private QueueService queueService;
    @Autowired private RedisConnectionFactory redisConnectionFactory;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
        attemptRepository.deleteAll();
        bookingRepository.deleteAll();
        concertRepository.deleteAll();
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    @Test
    void 보상_스케줄러_실행시_Booking_취소_좌석반납_Outbox_PUBLISHED() {
        Concert concert = savedConcert(1, ConcertStatus.OPEN);
        Booking booking = pendingBooking(concert, 1L);
        pendingOutbox(booking.getId(), concert.getId(), 1L);

        scheduler.processPending();

        Booking updated = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(BookingStatus.CANCELLED);

        Concert updated2 = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated2.getStatus()).isEqualTo(ConcertStatus.OPEN);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(10L);

        PaymentCompensationOutbox outbox = outboxRepository.findAll().get(0);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.getProcessedAt()).isNotNull();
    }

    @Test
    void SOLD_OUT_콘서트_보상시_OPEN으로_복원() {
        Concert concert = savedConcert(10, ConcertStatus.SOLD_OUT);
        Booking booking = pendingBooking(concert, 1L);
        pendingOutbox(booking.getId(), concert.getId(), 1L);

        scheduler.processPending();

        Concert updated = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ConcertStatus.OPEN);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(1L);
    }

    @Test
    void 이미_CANCELLED된_Booking은_멱등_처리_좌석_이중반납_없음() {
        Concert concert = savedConcert(1, ConcertStatus.OPEN);
        Booking booking = pendingBooking(concert, 1L);
        // CANCELLED without RELEASED must still complete its pending return
        booking.cancel();
        bookingRepository.save(booking);

        pendingOutbox(booking.getId(), concert.getId(), 1L);

        scheduler.processPending();

        Concert updated = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ConcertStatus.OPEN);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(10L);

        // Outbox is published after canonical release completes
        PaymentCompensationOutbox outbox = outboxRepository.findAll().get(0);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void PENDING_Outbox_없으면_아무것도_처리_안_함() {
        Concert concert = savedConcert(2, ConcertStatus.OPEN);

        scheduler.processPending();  // 처리할 outbox 없음

        Concert updated = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ConcertStatus.OPEN);  // 변경 없음
    }

    @Test
    void 존재하지_않는_bookingId로_3회_실행시_outbox_FAILED() {
        Concert concert = savedConcert(1, ConcertStatus.OPEN);
        // 실제로 존재하지 않는 bookingId → processOne 예외 발생
        pendingOutbox(99999L, concert.getId(), 1L);

        scheduler.processPending();  // retryCount = 1, PENDING 유지
        scheduler.processPending();  // retryCount = 2, PENDING 유지
        scheduler.processPending();  // retryCount = 3, FAILED

        PaymentCompensationOutbox outbox = outboxRepository.findAll().get(0);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(outbox.getRetryCount()).isEqualTo(3);
        assertThat(outbox.getLastError()).contains("Booking not found");
        assertThat(outbox.getFailedAt()).isNotNull();
        assertThat(meterRegistry.get("outbox.failed.current").gauge().value()).isEqualTo(1.0);
    }

    // --- helpers ---

    private Concert savedConcert(int bookedCount, ConcertStatus status) {
        Concert concert = concertRepository.save(Concert.builder()
                .title("테스트 콘서트")
                .totalSeats(10)
                .bookedCount(bookedCount)
                .status(status)
                .eventAt(LocalDateTime.now().minusMinutes(1))
                .price(90000)
                .build());
        queueService.initSeatCount(concert.getId(), Math.max(0, concert.getTotalSeats() - bookedCount));
        return concert;
    }

    private Booking pendingBooking(Concert concert, Long userId) {
        Booking b = bookingRepository.save(Booking.builder().concert(concert).userId(userId).build());
        studying.blog.support.BookingAttemptFixtures.seed(b, attemptRepository, redis);
        return b;  // status = PENDING_PAYMENT (Builder.Default)
    }

    @org.springframework.beans.factory.annotation.Autowired
    studying.blog.repository.PaymentRepository paymentRepository;
    private void pendingOutbox(Long bookingId, Long concertId, Long userId) {
        Long paymentId=99L;
        var booking=bookingRepository.findById(bookingId);
        if(booking.isPresent()) {
            var payment=studying.blog.domain.Payment.create(booking.get(),userId,concertId,90000,"MOCK",java.util.UUID.randomUUID().toString());
            payment.fail("fixture failure"); paymentRepository.saveAndFlush(payment); paymentId=payment.getId();
        }
        String payload = String.format(
                "{\"bookingId\":%d,\"concertId\":%d,\"userId\":%d,\"paymentId\":%d}",
                bookingId, concertId, userId, paymentId
        );
        outboxRepository.save(PaymentCompensationOutbox.create(payload));
    }
}
