package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.OutboxStatus;
import studying.blog.domain.PaymentCompensationOutbox;
import studying.blog.dto.PaymentCompensationAdminResponse;
import studying.blog.repository.PaymentCompensationOutboxRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentCompensationAdminService {

    private final PaymentCompensationOutboxRepository outboxRepository;

    @Transactional(readOnly = true)
    public List<PaymentCompensationAdminResponse> findByStatus(OutboxStatus status) {
        return outboxRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(PaymentCompensationAdminResponse::from)
                .toList();
    }

    @Transactional
    public PaymentCompensationAdminResponse retry(Long outboxId) {
        int updated = outboxRepository.retryFailed(outboxId);
        if (updated == 0) {
            PaymentCompensationOutbox outbox = outboxRepository.findById(outboxId)
                    .orElseThrow(() -> new IllegalArgumentException("Outbox not found: " + outboxId));
            throw new IllegalStateException(
                    "FAILED 상태의 Outbox만 재시도할 수 있습니다. currentStatus=" + outbox.getStatus());
        }

        PaymentCompensationOutbox retried = outboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox not found: " + outboxId));
        return PaymentCompensationAdminResponse.from(retried);
    }
}
