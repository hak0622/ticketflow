package studying.blog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
@Table(
        name = "payment",
        uniqueConstraints = {@UniqueConstraint(name = "uk_payment_idempotency_key", columnNames = {"idempotency_key"})},
        indexes = {
                @Index(name = "idx_payment_booking", columnList = "booking_id"),
                @Index(name = "idx_payment_user", columnList = "user_id"),
                @Index(name = "idx_payment_recovery", columnList = "recovery_phase,next_retry_at")
        }
)
public class Payment {
    @Column(name = "provider")
    private String provider;

    @Column(name = "pg_payment_key", length = 200, unique = true)
    private String pgPaymentKey;

    @Column(name = "confirm_key")
    private String confirmKey;

    @Column(name = "cancel_key")
    private String cancelKey;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_phase")
    private PaymentRecoveryPhase recoveryPhase;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;
    private LocalDateTime lastCheckedAt;
    private String lastPgStatus;
    private int retryCount;
    private boolean reviewRequired;

    @Column(length = 1000)
    private String lastError;

    @Version
    private Long version;
    private LocalDateTime updatedAt;
    private LocalDateTime canceledAt;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "concert_id", nullable = false)
    private Long concertId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public static Payment create(Booking booking, Long userId, Long concertId,
                                  Integer amount, String paymentMethod, String idempotencyKey) {
        return Payment.builder()
                .booking(booking)
                .userId(userId)
                .concertId(concertId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .idempotencyKey(idempotencyKey)
                .status(PaymentStatus.PENDING)
                .build();
    }

    public void complete() {
        this.status = PaymentStatus.COMPLETED;
        recoveryPhase = PaymentRecoveryPhase.NONE;
        nextRetryAt = null;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        this.status = PaymentStatus.FAILED;
        recoveryPhase = PaymentRecoveryPhase.NONE;
        nextRetryAt = null;
        this.failureReason = reason;
    }

    public void updatePaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public void preparePg(String paymentKey) {
        provider = "TOSS";
        pgPaymentKey = paymentKey;
        confirmKey = UUID.randomUUID().toString();
        cancelKey = UUID.randomUUID().toString();
        recoveryPhase = PaymentRecoveryPhase.APPROVAL_CHECK;
        nextRetryAt = retryDeadline(10);
    }

    public void phase(PaymentRecoveryPhase phase) {
        recoveryPhase = phase;
        nextRetryAt = retryDeadline(5);
    }

    public void observed(String pgStatus) {
        lastPgStatus = pgStatus;
        lastCheckedAt = LocalDateTime.now();
    }

    public void cancelRequired() {
        cancelReason = "TicketFlow booking cannot be finalized";
        phase(PaymentRecoveryPhase.CANCEL_REQUIRED);
    }

    public void canceled() {
        canceledAt = LocalDateTime.now();
        status = PaymentStatus.CANCELED;
        recoveryPhase = PaymentRecoveryPhase.NONE;
        nextRetryAt = null;
    }

    /** Also used by the coordinator to durably claim the first approval dispatch. */
    public void retry(String error) {
        retryCount++;
        lastError = error == null ? "Unknown" : error.substring(0, Math.min(1000, error.length()));
        nextRetryAt = retryDeadline(Math.min(60, 5L << Math.min(retryCount, 3)));
        if (retryCount >= 10) {
            reviewRequired = true;
        }
    }

    public boolean unresolved() {
        return status == PaymentStatus.PENDING;
    }

    private LocalDateTime retryDeadline(long seconds) {
        // Match DATETIME(6) before constructing an in-memory post-commit round snapshot.
        return LocalDateTime.now().plusSeconds(seconds).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    @PreUpdate
    void updating() {
        updatedAt = LocalDateTime.now();
    }

}
