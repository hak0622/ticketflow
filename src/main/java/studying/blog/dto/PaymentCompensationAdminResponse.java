package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.OutboxStatus;
import studying.blog.domain.PaymentCompensationOutbox;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class PaymentCompensationAdminResponse {
    private Long outboxId;
    private String eventType;
    private String payload;
    private OutboxStatus status;
    private int retryCount;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime failedAt;
    private LocalDateTime processedAt;

    public static PaymentCompensationAdminResponse from(PaymentCompensationOutbox outbox) {
        return new PaymentCompensationAdminResponse(
                outbox.getId(),
                outbox.getEventType(),
                outbox.getPayload(),
                outbox.getStatus(),
                outbox.getRetryCount(),
                outbox.getLastError(),
                outbox.getCreatedAt(),
                outbox.getFailedAt(),
                outbox.getProcessedAt()
        );
    }
}
