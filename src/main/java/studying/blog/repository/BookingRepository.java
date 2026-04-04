package studying.blog.repository;

import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import studying.blog.domain.Booking;
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
        b.createdAt
    )
    from Booking b
    join User u on u.id = b.userId
    where b.concert.id = :concertId
    order by b.createdAt desc
""")
    List<BookingAdminResponse> findAdminBookings(@Param("concertId") Long concertId);

    void deleteByConcert(Concert concert);
}
