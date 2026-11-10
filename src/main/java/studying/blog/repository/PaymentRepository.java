package studying.blog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import studying.blog.domain.Payment;
import studying.blog.domain.PaymentStatus;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    @Query("select p.booking.id from Payment p where p.id=:id")
    Long bookingIdForPayment(@Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id=:id")
    Optional<Payment> findLockedById(@Param("id") Long id);
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    java.util.List<Payment> findAllByBookingIdOrderByIdAsc(Long bookingId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.booking.id=:bookingId order by p.id")
    java.util.List<Payment> findAllLockedByBookingId(@Param("bookingId") Long bookingId);
    @Query("select p from Payment p where p.provider='TOSS' and p.status=studying.blog.domain.PaymentStatus.PENDING and p.nextRetryAt<=:now order by p.nextRetryAt")
    java.util.List<Payment> findRecoveryDue(@Param("now") java.time.LocalDateTime now, org.springframework.data.domain.Pageable pageable);
    long countByProviderAndStatus(String provider, PaymentStatus status);
    long countByProviderAndStatusAndReviewRequiredTrue(String provider, PaymentStatus status);
    Optional<Payment> findFirstByProviderAndStatusOrderByCreatedAtAsc(String provider, PaymentStatus status);
    // Legacy single-history test lookup. Production getMyPayment selects from the ordered history.
    Optional<Payment> findByBookingId(Long bookingId);
    boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    @Modifying
    @Query("DELETE FROM Payment p WHERE p.concertId = :concertId")
    void deleteAllByConcertId(@Param("concertId") Long concertId);
}
