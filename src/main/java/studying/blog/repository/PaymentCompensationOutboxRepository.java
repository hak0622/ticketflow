package studying.blog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import studying.blog.domain.OutboxStatus;
import studying.blog.domain.PaymentCompensationOutbox;

import java.util.List;

public interface PaymentCompensationOutboxRepository extends JpaRepository<PaymentCompensationOutbox, Long> {
    List<PaymentCompensationOutbox> findByStatus(OutboxStatus status);

    List<PaymentCompensationOutbox> findByStatusOrderByCreatedAtDesc(OutboxStatus status);

    long countByStatus(OutboxStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE PaymentCompensationOutbox o
        SET o.status = studying.blog.domain.OutboxStatus.PENDING,
            o.retryCount = 0,
            o.processedAt = null,
            o.lastError = null,
            o.failedAt = null
        WHERE o.id = :outboxId
          AND o.status = studying.blog.domain.OutboxStatus.FAILED
        """)
    int retryFailed(@Param("outboxId") Long outboxId);

    @Modifying
    @Query("""
        DELETE FROM PaymentCompensationOutbox o
        WHERE o.payload LIKE CONCAT('%"concertId":', :concertId, ',%')
           OR o.payload LIKE CONCAT('%"concertId":', :concertId, '}%')
        """)
    void deleteAllByConcertId(@Param("concertId") Long concertId);
}
