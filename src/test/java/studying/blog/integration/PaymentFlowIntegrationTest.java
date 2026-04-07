package studying.blog.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.Concert;
import studying.blog.domain.Payment;
import studying.blog.domain.PaymentStatus;
import studying.blog.dto.PaymentResponse;
import studying.blog.service.PaymentService;
import studying.blog.support.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentFlowIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PaymentService paymentService;

    @BeforeEach
    void setFailRateToZero() {
        ReflectionTestUtils.setField(paymentService, "failRate", 0);
    }

    @Test
    void shouldConfirmBookingAndCompletePaymentWhenPaymentSucceeds() {
        // Given
        Concert concert = savedConcert("Payment Success Concert", 10, 1);
        Long userId = 10L;
        Booking booking = savedPendingBooking(concert, userId);

        // When
        PaymentResponse response = paymentService.pay(
                concert.getId(),
                userId,
                paymentRequest(UUID.randomUUID().toString())
        );

        // Then
        Payment payment = paymentRepository.findById(response.getPaymentId()).orElseThrow();
        Booking reloadedBooking = bookingRepository.findById(booking.getId()).orElseThrow();

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void shouldKeepSinglePaymentWhenSameIdempotencyKeyIsRetried() {
        // Given
        Concert concert = savedConcert("Payment Idempotency Concert", 10, 1);
        Long userId = 20L;
        savedPendingBooking(concert, userId);
        String idempotencyKey = UUID.randomUUID().toString();

        // When
        PaymentResponse first = paymentService.pay(
                concert.getId(),
                userId,
                paymentRequest(idempotencyKey)
        );
        PaymentResponse second = paymentService.pay(
                concert.getId(),
                userId,
                paymentRequest(idempotencyKey)
        );

        // Then
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(first.getPaymentId()).isEqualTo(second.getPaymentId());
        assertThat(first.getStatus()).isEqualTo(second.getStatus());
        assertThat(bookingRepository.findByConcertIdAndUserId(concert.getId(), userId))
                .get()
                .extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.CONFIRMED);
    }
}
