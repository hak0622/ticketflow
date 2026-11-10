package studying.blog.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.PaymentRecoveryPhase;
import studying.blog.domain.PaymentStatus;
import studying.blog.dto.TossConfirmRequest;
import studying.blog.service.PaymentRetryRound;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the existing card fault scenarios again with Toss's actual wallet method value. */
class PaymentEasyPayIntegrationTest extends PaymentSagaIntegrationTest {
    @BeforeEach void useEasyPay() {
        pgMethod = "간편결제";
    }

    @Test void easyPayApprovalConfirmsAndSameOrderDoesNotApproveAgain() throws Exception {
        var concert = savedConcert("wallet approval", 3, 0);
        grantAdmitted(concert.getId(), 1L);
        bookings.book(concert.getId(), 1L, UUID.randomUUID().toString());
        var request = request(UUID.randomUUID().toString());
        var response = payments.tossConfirm(concert.getId(), 1L, request);
        var payment = paymentRepository.findById(response.getPaymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getPaymentMethod()).isEqualTo("간편결제");
        assertThat(payment.getRecoveryPhase()).isEqualTo(PaymentRecoveryPhase.NONE);
        assertThat(payment.getNextRetryAt()).isNull();
        assertThat(bookingRepository.findById(response.getBookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(payments.tossConfirm(concert.getId(), 1L, request).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(2);
        assertThat(outboxRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"paymentKey", "orderId", "amount"})
    void walletStillRejectsIdentityOrAmountMismatch(String field) throws Exception {
        var concert = savedConcert("wallet mismatch", 3, 0);
        grantAdmitted(concert.getId(), 1L);
        bookings.book(concert.getId(), 1L, UUID.randomUUID().toString());
        if (field.equals("paymentKey")) pgPaymentKey = "different-key";
        if (field.equals("orderId")) pgOrderOverride = "different-order";
        if (field.equals("amount")) pgAmount = 1;
        var response = payments.tossConfirm(concert.getId(), 1L, request(UUID.randomUUID().toString()));
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(bookingRepository.findById(response.getBookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(2);
        assertThat(outboxRepository.count()).isZero();
        pgPaymentKey = "local-only";
        pgOrderOverride = null;
        pgAmount = 90000;
        saga.recover(response.getPaymentId());
        assertThat(paymentRepository.findById(response.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test void virtualAccountIsNotImplicitlyEnabled() throws Exception {
        var concert = savedConcert("unsupported method", 3, 0);
        grantAdmitted(concert.getId(), 1L);
        bookings.book(concert.getId(), 1L, UUID.randomUUID().toString());
        pgMethod = "가상계좌";
        var response = payments.tossConfirm(concert.getId(), 1L, request(UUID.randomUUID().toString()));
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentRepository.findById(response.getPaymentId()).orElseThrow().getLastError())
                .contains("Unsupported PG payment method");
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(2);
        assertThat(outboxRepository.count()).isZero();
    }

    @Test void cancellationIntentAndCompletedCancellationCannotBeReversedByOldWalletDone() throws Exception {
        var concert = savedConcert("stale wallet approval", 3, 0);
        grantAdmitted(concert.getId(), 1L);
        bookings.book(concert.getId(), 1L, UUID.randomUUID().toString());
        loseReply = true;
        var response = payments.tossConfirm(concert.getId(), 1L, request(UUID.randomUUID().toString()));
        loseReply = false;
        var payment = paymentRepository.findById(response.getPaymentId()).orElseThrow();
        var round = PaymentRetryRound.from(payment);
        var oldDone = pg.lookup("local-only");
        jdbcTemplate.update("UPDATE payment SET recovery_phase='CANCEL_REQUIRED', cancel_reason=? WHERE id=?",
                "TicketFlow booking cannot be finalized", payment.getId());
        Object target = AopTestUtils.getUltimateTargetObject(saga);
        ReflectionTestUtils.invokeMethod(target, "applyResult", payment.getId(), oldDone, round);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(bookingRepository.findById(response.getBookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
        saga.recover(payment.getId());
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
        var event = outboxRepository.findAll().get(0).getId();
        compensation.process(event);
        compensation.process(event);
        ReflectionTestUtils.invokeMethod(target, "applyResult", payment.getId(), oldDone, round);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(bookingRepository.findById(response.getBookingId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(3);
        assertThat(cancelCalls.get()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    private TossConfirmRequest request(String order) throws Exception {
        return json.readValue("{\"paymentKey\":\"local-only\",\"orderId\":\"" + order
                + "\",\"amount\":90000}", TossConfirmRequest.class);
    }
}
