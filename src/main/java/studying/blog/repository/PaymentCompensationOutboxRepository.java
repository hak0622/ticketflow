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

    @Modifying
    @Query("""
        DELETE FROM PaymentCompensationOutbox o
        WHERE o.payload LIKE CONCAT('%"concertId":', :concertId, ',%')
           OR o.payload LIKE CONCAT('%"concertId":', :concertId, '}%')
        """)
    void deleteAllByConcertId(@Param("concertId") Long concertId);
}
