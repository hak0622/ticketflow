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
        name = "payment_compensation_outbox",
        indexes = {@Index(name = "idx_outbox_status", columnList = "status")}
)
public class PaymentCompensationOutbox {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public static PaymentCompensationOutbox create(String payload) {
        return PaymentCompensationOutbox.builder()
                .eventType("payment.compensation")
                .payload(payload)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.processedAt = LocalDateTime.now();
    }

    public void incrementRetry(int maxRetry) {
        this.retryCount++;
        if (this.retryCount >= maxRetry) {
            this.status = OutboxStatus.FAILED;
        }
    }
}
