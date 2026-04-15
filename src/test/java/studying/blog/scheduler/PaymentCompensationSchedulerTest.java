package studying.blog.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import studying.blog.domain.*;
import studying.blog.repository.*;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class PaymentCompensationSchedulerTest {

    @Autowired private PaymentCompensationScheduler scheduler;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentCompensationOutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        bookingRepository.deleteAll();
        concertRepository.deleteAll();
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
    }

    @Test
    void 이미_CANCELLED된_Booking은_멱등_처리_좌석_이중반납_없음() {
        Concert concert = savedConcert(1, ConcertStatus.OPEN);
        Booking booking = pendingBooking(concert, 1L);
        // 미리 cancel 처리
        booking.cancel();
        bookingRepository.save(booking);

        pendingOutbox(booking.getId(), concert.getId(), 1L);

        scheduler.processPending();

        Concert updated = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ConcertStatus.OPEN);

        // Outbox는 PUBLISHED (멱등 완료 처리)
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
    }

    // --- helpers ---

    private Concert savedConcert(int bookedCount, ConcertStatus status) {
        return concertRepository.save(Concert.builder()
                .title("테스트 콘서트")
                .totalSeats(10)
                .bookedCount(bookedCount)
                .status(status)
                .eventAt(LocalDateTime.now().minusMinutes(1))
                .price(90000)
                .build());
    }

    private Booking pendingBooking(Concert concert, Long userId) {
        return bookingRepository.save(Booking.builder()
                .concert(concert)
                .userId(userId)
                .build());  // status = PENDING_PAYMENT (Builder.Default)
    }

    private void pendingOutbox(Long bookingId, Long concertId, Long userId) {
        String payload = String.format(
                "{\"bookingId\":%d,\"concertId\":%d,\"userId\":%d,\"paymentId\":99}",
                bookingId, concertId, userId
        );
        outboxRepository.save(PaymentCompensationOutbox.create(payload));
    }
}
