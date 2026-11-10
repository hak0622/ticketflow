package studying.blog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.Concert;
import studying.blog.domain.Payment;
import studying.blog.domain.PaymentCompensationOutbox;
import studying.blog.domain.PaymentRecoveryPhase;
import studying.blog.domain.PaymentStatus;
import studying.blog.dto.PaymentResponse;
import studying.blog.dto.TossConfirmRequest;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.repository.PaymentCompensationOutboxRepository;
import studying.blog.repository.PaymentRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/** Durable payment coordinator. External HTTP never executes inside a DB transaction. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSaga {
    private final PlatformTransactionManager manager;
    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final ConcertRepository concerts;
    private final PaymentCompensationOutboxRepository outbox;
    private final TossPaymentClient pg;
    private final ObjectMapper json;

    public PaymentResponse confirm(Long concertId, Long userId, TossConfirmRequest request) {
        validateRequest(request);
        // Commit failure escapes: never contact PG unless durable preparation is known successful.
        Payment preparedPayment = preparePayment(concertId, userId, request);
        PaymentRetryRound round = claimInitialDispatch(preparedPayment);
        if (round != null) {
            try {
                TossPaymentClient.PgResult result = pg.approve(
                        preparedPayment.getPgPaymentKey(), preparedPayment.getIdempotencyKey(),
                        preparedPayment.getAmount(), preparedPayment.getConfirmKey());
                applyResult(preparedPayment.getId(), result, round);
            } catch (RuntimeException failure) {
                recordRetry(preparedPayment.getId(), failure, round);
            }
        }
        return response(preparedPayment.getId());
    }

    public PaymentResponse response(Long paymentId) {
        return transaction().execute(status ->
                PaymentResponse.from(payments.findById(paymentId).orElseThrow()));
    }

    public void recover(Long paymentId) {
        Payment payment = transaction().execute(status -> payments.findById(paymentId).orElseThrow());
        recover(payment, PaymentRetryRound.from(payment));
    }

    public void recover(Long paymentId, PaymentRetryRound selectedRound) {
        if (!Objects.equals(paymentId, selectedRound.paymentId())) {
            throw new IllegalArgumentException("Payment retry round identity mismatch");
        }
        Payment payment = transaction().execute(status -> payments.findById(paymentId).orElseThrow());
        recover(payment, selectedRound);
    }

    private void recover(Payment payment, PaymentRetryRound selectedRound) {
        Long paymentId = payment.getId();
        if (!payment.unresolved() || payment.getPgPaymentKey() == null) {
            return;
        }
        PaymentRetryRound round = selectedRound;
        try {
            TossPaymentClient.PgResult result = pg.lookup(payment.getPgPaymentKey());
            round = applyResult(paymentId, result, round);
            // Re-read intent; terminal success is applicable even for a stale selected round.
            Payment currentPayment = transaction().execute(status -> payments.findById(paymentId).orElseThrow());
            if (shouldRequestCancellation(result, currentPayment)) {
                round = markCancellationCheck(paymentId, currentPayment, round);
                applyResult(paymentId, pg.cancel(currentPayment.getPgPaymentKey(),
                        currentPayment.getCancelReason(), currentPayment.getAmount(), currentPayment.getCancelKey()), round);
            }
        } catch (RuntimeException failure) {
            recordRetry(paymentId, failure, round);
        }
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private void validateRequest(TossConfirmRequest request) {
        if (request.getPaymentKey() == null || request.getPaymentKey().isBlank()
                || request.getPaymentKey().length() > 200 || request.getOrderId() == null
                || request.getOrderId().length() < 6 || request.getOrderId().length() > 64
                || request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("잘못된 결제 요청입니다.");
        }
    }

    private void validateIdentity(Payment payment, Long concertId, Long userId, TossConfirmRequest request) {
        if (!Objects.equals(payment.getIdempotencyKey(), request.getOrderId())
                || !Objects.equals(payment.getConcertId(), concertId)
                || !Objects.equals(payment.getUserId(), userId)
                || !Objects.equals(payment.getAmount(), request.getAmount())
                || (payment.getProvider() != null
                    && (!Objects.equals(payment.getPgPaymentKey(), request.getPaymentKey())
                        || !"TOSS".equals(payment.getProvider())))
                || (payment.getProvider() == null && payment.unresolved())) {
            throw new IllegalArgumentException("결제 요청 정보가 일치하지 않습니다.");
        }
    }

    private Payment preparePayment(Long concertId, Long userId, TossConfirmRequest request) {
        return transaction().execute(status -> {
            var cachedPayment = payments.findByIdempotencyKey(request.getOrderId());
            if (cachedPayment.isPresent()) {
                validateIdentity(cachedPayment.get(), concertId, userId, request);
                return cachedPayment.get();
            }
            Booking booking = bookings.findLockedByConcertIdAndUserId(concertId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("예매 내역이 없습니다."));
            // Another request may have created the Payment while this request waited for Booking.
            var existingPayment = payments.findByIdempotencyKey(request.getOrderId());
            if (existingPayment.isPresent()) {
                validateIdentity(existingPayment.get(), concertId, userId, request);
                return existingPayment.get();
            }
            if (booking.getStatus() != BookingStatus.PENDING_PAYMENT
                    || !booking.getCreatedAt().plusMinutes(30).isAfter(LocalDateTime.now())) {
                throw new IllegalStateException("결제할 수 없는 예매입니다.");
            }
            if (!payments.findAllByBookingIdOrderByIdAsc(booking.getId()).isEmpty()) {
                throw new IllegalStateException("기존 결제를 먼저 확인해주세요.");
            }
            Concert concert = concerts.findById(concertId).orElseThrow();
            Integer amount = expectedAmount(concert);
            if (!Objects.equals(amount, request.getAmount())) {
                throw new IllegalArgumentException("결제 금액이 일치하지 않습니다.");
            }
            Payment payment = Payment.create(booking, userId, concertId, amount, "TOSS", request.getOrderId());
            payment.preparePg(request.getPaymentKey());
            payments.saveAndFlush(payment);
            return payment;
        });
    }

    private Integer expectedAmount(Concert concert) {
        Integer amount = concert.getPrice();
        if (amount != null && concert.getDiscountRate() != null
                && concert.getDiscountRate() > 0 && concert.getDiscountRate() <= 100) {
            amount = (int) (Math.round(amount * (1 - concert.getDiscountRate() / 100.0) / 100.0) * 100);
        }
        return amount;
    }

    private PaymentRetryRound claimInitialDispatch(Payment preparedPayment) {
        return transaction().execute(status -> {
            bookings.findLockedById(preparedPayment.getBooking().getId()).orElseThrow();
            Payment payment = payments.findLockedById(preparedPayment.getId()).orElseThrow();
            // retryCount also records the first dispatch claim. A crash after this commit may
            // precede HTTP transmission; neither a replay nor the Worker blindly approves again.
            if (payment.getRetryCount() != 0 || payment.getStatus() != PaymentStatus.PENDING
                    || payment.getRecoveryPhase() != PaymentRecoveryPhase.APPROVAL_CHECK) {
                return null;
            }
            payment.retry("Initial approval dispatched or may be dispatched");
            return PaymentRetryRound.from(payment);
        });
    }

    private boolean cancellationInProgress(Payment payment) {
        return payment.getRecoveryPhase() == PaymentRecoveryPhase.CANCEL_REQUIRED
                || payment.getRecoveryPhase() == PaymentRecoveryPhase.CANCEL_CHECK;
    }

    private boolean shouldRequestCancellation(TossPaymentClient.PgResult result, Payment payment) {
        return "DONE".equals(result.status()) && Objects.equals(result.balanceAmount(), payment.getAmount())
                && supportedMethod(result) && payment.unresolved() && cancellationInProgress(payment);
    }

    private PaymentRetryRound markCancellationCheck(Long paymentId, Payment snapshot, PaymentRetryRound round) {
        return transaction().execute(status -> {
            bookings.findLockedById(snapshot.getBooking().getId()).orElseThrow();
            Payment payment = payments.findLockedById(paymentId).orElseThrow();
            if (round.matches(payment) && payment.getRecoveryPhase() != PaymentRecoveryPhase.CANCEL_CHECK) {
                payment.phase(PaymentRecoveryPhase.CANCEL_CHECK);
                return PaymentRetryRound.from(payment);
            }
            return round;
        });
    }

    private PaymentRetryRound applyResult(Long paymentId, TossPaymentClient.PgResult result, PaymentRetryRound round) {
        return transaction().execute(status -> {
            Booking booking = bookings.findLockedById(payments.bookingIdForPayment(paymentId)).orElseThrow();
            Payment payment = payments.findLockedById(paymentId).orElseThrow();
            if (!payment.unresolved()) {
                return round;
            }
            log.info("[PAYMENT_SAGA] paymentId={} phase={} pgStatus={}", paymentId,
                    payment.getRecoveryPhase(), result == null ? "EMPTY" : result.status());
            validatePgResult(payment, result);
            payment.observed(result.status());
            if (!supportedMethod(result)) {
                retryIfCurrent(payment, round, "Unsupported PG payment method; manual review required");
                return round;
            }
            boolean cancellation = cancellationInProgress(payment);
            if ("DONE".equals(result.status())) {
                return applyApproval(payment, booking, result, cancellation, round);
            } else if ("CANCELED".equals(result.status()) && Integer.valueOf(0).equals(result.balanceAmount())) {
                payment.canceled();
                saveReturnEvent(payment, booking);
            } else if (("ABORTED".equals(result.status()) || "EXPIRED".equals(result.status())) && !cancellation) {
                payment.fail("PG final status: " + result.status());
                saveReturnEvent(payment, booking);
            } else {
                retryIfCurrent(payment, round, "PG status requires review: " + result.status());
            }
            return round;
        });
    }

    private void validatePgResult(Payment payment, TossPaymentClient.PgResult result) {
        if (result == null || !Objects.equals(payment.getPgPaymentKey(), result.paymentKey())
                || !Objects.equals(payment.getIdempotencyKey(), result.orderId())
                || !Objects.equals(payment.getAmount(), result.totalAmount())) {
            throw new IllegalStateException("PG identity/amount mismatch");
        }
    }

    private boolean supportedMethod(TossPaymentClient.PgResult result) {
        // Toss's CARD payment window also offers wallets, reported as 간편결제.
        return "CARD".equals(result.method()) || "카드".equals(result.method())
                || "간편결제".equals(result.method());
    }

    /** Runs inside the existing applyResult transaction; introduces no transaction boundary. */
    private PaymentRetryRound applyApproval(Payment payment, Booking booking, TossPaymentClient.PgResult result,
                                           boolean cancellation, PaymentRetryRound round) {
        if (!Objects.equals(result.balanceAmount(), payment.getAmount())) {
            retryIfCurrent(payment, round, "PG balance mismatch; manual review required");
            return round;
        }
        if (cancellation) {
            if (round.matches(payment) && payment.getRecoveryPhase() != PaymentRecoveryPhase.CANCEL_CHECK) {
                payment.phase(PaymentRecoveryPhase.CANCEL_CHECK);
                return PaymentRetryRound.from(payment);
            }
            return round;
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            boolean ownRound = round.matches(payment);
            payment.cancelRequired();
            return ownRound ? PaymentRetryRound.from(payment) : round;
        }
        payment.updatePaymentMethod(result.method());
        payment.complete();
        booking.confirm();
        return round;
    }

    private void saveReturnEvent(Payment payment, Booking booking) {
        try {
            outbox.save(PaymentCompensationOutbox.create(json.writeValueAsString(Map.of(
                    "bookingId", booking.getId(), "concertId", payment.getConcertId(),
                    "userId", payment.getUserId(), "paymentId", payment.getId()))));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void recordRetry(Long paymentId, Exception failure, PaymentRetryRound round) {
        try {
            transaction().executeWithoutResult(status -> {
                bookings.findLockedById(payments.bookingIdForPayment(paymentId)).orElseThrow();
                Payment payment = payments.findLockedById(paymentId).orElseThrow();
                retryIfCurrent(payment, round, failure.toString());
            });
        } catch (RuntimeException unavailable) {
            log.error("[PAYMENT_RECOVERY][DB_UNAVAILABLE] paymentId={}", paymentId, unavailable);
        }
    }
    private void retryIfCurrent(Payment payment, PaymentRetryRound round, String failure) {
        if (round.matches(payment)) {
            payment.retry(failure);
        } else {
            log.debug("[PAYMENT_RETRY_SKIPPED] paymentId={} selectedCount={} currentCount={} selectedVersion={} currentVersion={}",
                    payment.getId(), round.retryCount(), payment.getRetryCount(), round.version(), payment.getVersion());
        }
    }

}
