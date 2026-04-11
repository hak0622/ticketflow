package studying.blog.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import studying.blog.domain.*;
import studying.blog.repository.*;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class BookingExpirySchedulerTest {

    @Autowired private BookingExpiryScheduler scheduler;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        concertRepository.deleteAll();
    }

    @Test
    void 만료된_PENDING_PAYMENT_예약은_취소되고_좌석_반납() {
        Concert concert = savedConcert(1, ConcertStatus.OPEN);
        Booking booking = savedBooking(concert, 1L);
        setCreatedAt(booking.getId(), LocalDateTime.now().minusMinutes(31));

        scheduler.expireStaleBookings();

        Booking updated = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(BookingStatus.CANCELLED);

        Concert updated2 = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated2.getBookedCount()).isEqualTo(0);
    }

    @Test
    void 만료기준_미달_예약은_처리_안_함() {
        Concert concert = savedConcert(1, ConcertStatus.OPEN);
        Booking booking = savedBooking(concert, 1L);
        setCreatedAt(booking.getId(), LocalDateTime.now().minusMinutes(29));

        scheduler.expireStaleBookings();

        Booking updated = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);  // 변경 없음

        Concert updated2 = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated2.getBookedCount()).isEqualTo(1);  // 변경 없음
    }

    @Test
    void CONFIRMED_예약은_만료_처리_대상_아님() {
        Concert concert = savedConcert(1, ConcertStatus.OPEN);
        Booking booking = savedBooking(concert, 1L);
        booking.confirm();
        bookingRepository.save(booking);
        setCreatedAt(booking.getId(), LocalDateTime.now().minusMinutes(31));

        scheduler.expireStaleBookings();

        // CONFIRMED는 status=CONFIRMED이므로 쿼리 조건 불일치 → 처리 안 됨
        Booking updated = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        Concert updated2 = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated2.getBookedCount()).isEqualTo(1);
    }

    @Test
    void 같은_콘서트의_만료_예약_3건을_한번에_처리() {
        Concert concert = savedConcert(3, ConcertStatus.OPEN);
        List<Booking> bookings = List.of(
                savedBooking(concert, 1L),
                savedBooking(concert, 2L),
                savedBooking(concert, 3L)
        );
        bookings.forEach(b -> setCreatedAt(b.getId(), LocalDateTime.now().minusMinutes(31)));

        scheduler.expireStaleBookings();

        bookings.forEach(b -> {
            Booking updated = bookingRepository.findById(b.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        });

        Concert updated = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated.getBookedCount()).isEqualTo(0);  // 3건 모두 반납
    }

    @Test
    void SOLD_OUT_콘서트_만료_처리시_OPEN으로_복원() {
        Concert concert = savedConcert(10, ConcertStatus.SOLD_OUT);
        Booking booking = savedBooking(concert, 1L);
        setCreatedAt(booking.getId(), LocalDateTime.now().minusMinutes(31));

        scheduler.expireStaleBookings();

        Concert updated = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ConcertStatus.OPEN);
        assertThat(updated.getBookedCount()).isEqualTo(9);
    }

    // --- helpers ---

    private Concert savedConcert(int bookedCount, ConcertStatus status) {
        return concertRepository.save(Concert.builder()
                .title("만료 테스트 콘서트")
                .totalSeats(10)
                .bookedCount(bookedCount)
                .status(status)
                .eventAt(LocalDateTime.now().minusMinutes(1))
                .price(50000)
                .build());
    }

    private Booking savedBooking(Concert concert, Long userId) {
        return bookingRepository.save(Booking.builder()
                .concert(concert)
                .userId(userId)
                .build());
    }

    /** H2의 @PrePersist 이후 createdAt을 직접 과거 시각으로 덮어쓴다. */
    private void setCreatedAt(Long bookingId, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "UPDATE booking SET created_at = ? WHERE id = ?",
                createdAt, bookingId
        );
    }
}
