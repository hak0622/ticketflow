package studying.blog.service;

import studying.blog.domain.Payment;
import studying.blog.domain.PaymentRecoveryPhase;

import java.time.LocalDateTime;
import java.util.Objects;

/** Immutable candidate identity. Version is diagnostic: PG observation also updates it. */
public record PaymentRetryRound(Long paymentId, int retryCount, LocalDateTime nextRetryAt,
                                PaymentRecoveryPhase recoveryPhase, Long version) {
    public static PaymentRetryRound from(Payment payment) {
        return new PaymentRetryRound(payment.getId(), payment.getRetryCount(), payment.getNextRetryAt(),
                payment.getRecoveryPhase(), payment.getVersion());
    }

    public boolean matches(Payment payment) {
        return payment.unresolved() && Objects.equals(paymentId, payment.getId())
                && retryCount == payment.getRetryCount()
                && Objects.equals(nextRetryAt, payment.getNextRetryAt())
                && recoveryPhase == payment.getRecoveryPhase();
    }
}
