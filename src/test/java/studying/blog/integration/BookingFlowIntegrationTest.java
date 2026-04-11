package studying.blog.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.dto.BookingResult;
import studying.blog.service.BookingService;
import studying.blog.support.IntegrationTestSupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class BookingFlowIntegrationTest extends IntegrationTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private BookingService bookingService;

    @Test
    void shouldCreateBookingWhenUserIsAdmitted() {
        // Given
        Concert concert = savedConcert("Admitted Booking Concert", 10, 0);
        Long userId = 1L;
        grantAdmitted(concert.getId(), userId);

        // When
        BookingResult result = bookingService.book(concert.getId(), userId);

        // Then
        List<Booking> bookings = bookingRepository.findAllByConcertId(concert.getId());
        Concert reloadedConcert = concertRepository.findById(concert.getId()).orElseThrow();

        assertThat(result.getConcertId()).isEqualTo(concert.getId());
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getUserId()).isEqualTo(userId);
        assertThat(bookings.get(0).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(reloadedConcert.getBookedCount()).isEqualTo(1);
        assertThat(reloadedConcert.getStatus()).isEqualTo(ConcertStatus.OPEN);
    }

    @Test
    void shouldRejectBookingWhenUserIsWaiting() {
        // Given
        Concert concert = savedConcert("Waiting Booking Concert", 10, 0);
        Long userId = 2L;

        // When / Then
        assertThatThrownBy(() -> bookingService.book(concert.getId(), userId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(bookingRepository.findAllByConcertId(concert.getId())).isEmpty();
        assertThat(concertRepository.findById(concert.getId()).orElseThrow().getBookedCount()).isZero();
    }

    @Test
    void shouldCreateOnlyOneBookingWhenSameUserRequestsBookingConcurrently() throws Exception {
        // Given
        Concert concert = savedConcert("Concurrent Booking Concert", 10, 0);
        Long userId = 3L;
        grantAdmitted(concert.getId(), userId);

        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<String> outcomes = new CopyOnWriteArrayList<>();

        // When
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    BookingResult result = bookingService.book(concert.getId(), userId);
                    outcomes.add("SUCCESS:" + result.getStatus());
                } catch (Exception e) {
                    outcomes.add("FAILED:" + e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executorService.shutdown();

        // Then
        List<Booking> bookings = bookingRepository.findAllByConcertId(concert.getId());
        Concert reloadedConcert = concertRepository.findById(concert.getId()).orElseThrow();

        assertThat(outcomes).hasSize(threadCount);
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getUserId()).isEqualTo(userId);
        assertThat(bookings.get(0).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(reloadedConcert.getBookedCount()).isEqualTo(1);
    }
}
