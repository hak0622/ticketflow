package studying.blog.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.Concert;
import studying.blog.domain.OutboxStatus;
import studying.blog.domain.Payment;
import studying.blog.domain.PaymentCompensationOutbox;
import studying.blog.domain.PaymentStatus;
import studying.blog.scheduler.BookingExpiryScheduler;
import studying.blog.scheduler.PaymentCompensationScheduler;
import studying.blog.support.IntegrationTestSupport;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class BookingLifecycleIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PaymentCompensationScheduler paymentCompensationScheduler;

    @Autowired
    private BookingExpiryScheduler bookingExpiryScheduler;

    @Test
    void shouldCancelBookingAndDecreaseBookedCountWhenFailedPaymentIsCompensated() {
        // Given
        Concert concert = savedConcert("Compensation Concert", 10, 1);
        Long userId = 31L;
        Booking booking = savedPendingBooking(concert, userId);
        Payment payment = savedFailedPayment(booking, userId, concert.getId(), UUID.randomUUID().toString());
        PaymentCompensationOutbox outbox = savedPendingOutbox(booking.getId(), concert.getId(), userId, payment.getId());

        // When
        paymentCompensationScheduler.processPending();

        // Then
        Booking reloadedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        Concert reloadedConcert = concertRepository.findById(concert.getId()).orElseThrow();
        PaymentCompensationOutbox reloadedOutbox = outboxRepository.findById(outbox.getId()).orElseThrow();

        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reloadedConcert.getBookedCount()).isEqualTo(0);
        assertThat(reloadedOutbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(reloadedOutbox.getProcessedAt()).isNotNull();
    }

    @Test
    void shouldCancelExpiredPendingBookingAndDecreaseBookedCount() {
        // Given
        Concert concert = savedConcert("Expired Pending Concert", 10, 1);
        Long userId = 32L;
        Booking booking = savedPendingBooking(concert, userId);
        backdateBooking(booking.getId(), LocalDateTime.now().minusMinutes(31));

        // When
        bookingExpiryScheduler.expireStaleBookings();

        // Then
        Booking reloadedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        Concert reloadedConcert = concertRepository.findById(concert.getId()).orElseThrow();

        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(reloadedConcert.getBookedCount()).isEqualTo(0);
        assertThat(paymentRepository.findByBookingId(booking.getId())).isEmpty();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void shouldKeepConfirmedBookingAndBookedCountWhenExpiredBookingSchedulerRuns() {
        // Given
        Concert concert = savedConcert("Confirmed Booking Concert", 10, 1);
        Long userId = 33L;
        Booking booking = savedPendingBooking(concert, userId);
        booking.confirm();
        bookingRepository.save(booking);
        Payment payment = savedCompletedPayment(booking, userId, concert.getId(), UUID.randomUUID().toString());
        backdateBooking(booking.getId(), LocalDateTime.now().minusMinutes(31));

        // When
        bookingExpiryScheduler.expireStaleBookings();

        // Then
        Booking reloadedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        Concert reloadedConcert = concertRepository.findById(concert.getId()).orElseThrow();

        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(reloadedConcert.getBookedCount()).isEqualTo(1);
        assertThat(outboxRepository.count()).isZero();
    }

    private Payment savedFailedPayment(Booking booking, Long userId, Long concertId, String idempotencyKey) {
        Payment payment = Payment.create(
                booking,
                userId,
                concertId,
                booking.getConcert().getPrice(),
                "CARD",
                idempotencyKey
        );
        payment.fail("Mock PG failure for compensation integration test");
        return paymentRepository.save(payment);
    }

    private Payment savedCompletedPayment(Booking booking, Long userId, Long concertId, String idempotencyKey) {
        Payment payment = Payment.create(
                booking,
                userId,
                concertId,
                booking.getConcert().getPrice(),
                "CARD",
                idempotencyKey
        );
        payment.complete();
        return paymentRepository.save(payment);
    }

    private PaymentCompensationOutbox savedPendingOutbox(Long bookingId, Long concertId, Long userId, Long paymentId) {
        String payload = String.format(
                "{\"bookingId\":%d,\"concertId\":%d,\"userId\":%d,\"paymentId\":%d}",
                bookingId, concertId, userId, paymentId
        );
        return outboxRepository.save(PaymentCompensationOutbox.create(payload));
    }

    private void backdateBooking(Long bookingId, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "UPDATE booking SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(createdAt),
                bookingId
        );
    }
}
