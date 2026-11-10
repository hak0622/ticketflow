package studying.blog.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import studying.blog.domain.BookingAttempt;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingAttemptRepository extends JpaRepository<BookingAttempt, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "1000"))
    @Query("select a from BookingAttempt a where a.id = :id")
    Optional<BookingAttempt> findLockedById(@Param("id") String id);

    Optional<BookingAttempt> findByBookingId(Long bookingId);
    boolean existsByConcertId(Long concertId);
    List<BookingAttempt> findAllByConcertId(Long concertId);
    void deleteAllByConcertId(Long concertId);

    @Query("""
        select a.id from BookingAttempt a
        where a.status in (studying.blog.domain.BookingAttemptStatus.REQUESTED,
                           studying.blog.domain.BookingAttemptStatus.RELEASE_PENDING)
          and a.nextRetryAt <= :now order by a.nextRetryAt, a.id
        """)
    List<String> findRecoveryCandidates(@Param("now") LocalDateTime now, Pageable pageable);
}
