package studying.blog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.Concert;
import studying.blog.dto.BookingAdminResponse;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    boolean existsByConcertIdAndUserId(Long concertId, Long userId);
    Optional<Booking> findByConcertIdAndUserId(Long concertId, Long userId);

    List<Booking> findAllByConcertId(Long concertId);

    @Query("select b from Booking b join fetch b.concert where b.userId = :userId order by b.createdAt desc")
    List<Booking> findByUserIdWithConcert(@Param("userId") Long userId);

    @Query("""
    select new studying.blog.dto.BookingAdminResponse(
        b.id,
        b.userId,
        u.email,
        u.nickname,
        b.concert.id,
        b.concert.title,
        b.status,
        b.createdAt
    )
    from Booking b
    join User u on u.id = b.userId
    where b.concert.id = :concertId
    order by b.createdAt desc
""")
    List<BookingAdminResponse> findAdminBookings(@Param("concertId") Long concertId);

    void deleteByConcert(Concert concert);

    int countByConcertId(Long concertId);

    @Modifying
    @Query("DELETE FROM Booking b WHERE b.concert.id = :concertId")
    void deleteAllByConcertId(@Param("concertId") Long concertId);

    @Query("""
        select b from Booking b
        join fetch b.concert
        where b.status = :status
        and b.createdAt < :threshold
    """)
    List<Booking> findAllByStatusAndCreatedAtBefore(
            @Param("status") BookingStatus status,
            @Param("threshold") java.time.LocalDateTime threshold
    );
}
