package studying.blog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import studying.blog.domain.OutboxStatus;
import studying.blog.domain.PaymentCompensationOutbox;

import java.util.List;

public interface PaymentCompensationOutboxRepository extends JpaRepository<PaymentCompensationOutbox, Long> {
    List<PaymentCompensationOutbox> findByStatus(OutboxStatus status);
}
