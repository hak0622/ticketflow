package studying.blog.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

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
                @Index(name = "idx_payment_user", columnList = "user_id")
        }
)
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    public void updatePaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
}
