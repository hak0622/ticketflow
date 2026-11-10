package ticketflow.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import studying.blog.domain.Booking;
import studying.blog.config.SeatCountInitializer;
import studying.blog.config.QuerydslConfig;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.service.QueueService;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = SeatCountInitializerTest.JpaTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SeatCountInitializerTest {
    @Configuration
    @EntityScan("studying.blog.domain")
    @EnableJpaRepositories("studying.blog.repository")
    @Import(QuerydslConfig.class)
    static class JpaTestConfiguration {}

    @Autowired private ConcertRepository concerts;
    @Autowired private BookingRepository bookings;

    private final QueueService queueService = mock(QueueService.class);

    @Test
    void countsActualActiveBookingsInsteadOfConcertCounter() {
        Concert concert = concert(10, 9, ConcertStatus.OPEN);
        booking(concert, 1L, BookingStatus.PENDING_PAYMENT);
        booking(concert, 2L, BookingStatus.PENDING_PAYMENT);
        booking(concert, 3L, BookingStatus.CONFIRMED);
        booking(concert, 4L, BookingStatus.CONFIRMED);
        booking(concert, 5L, BookingStatus.CONFIRMED);
        booking(concert, 6L, BookingStatus.CANCELLED);
        // Other concerts must not affect this concert's available quantity.
        booking(concert(10, 0, ConcertStatus.CLOSED), 7L, BookingStatus.CONFIRMED);

        initialize();

        verify(queueService).initSeatCountIfAbsent(concert.getId(), 5);
        verifyNoMoreInteractions(queueService);
    }

    @Test
    void expiredButNotCancelledBookingStillOccupiesQuantity() {
        Concert concert = concert(10, 0, ConcertStatus.OPEN);
        Booking booking = booking(concert, 1L, BookingStatus.PENDING_PAYMENT);
        ReflectionTestUtils.setField(booking, "createdAt", LocalDateTime.now().minusHours(1));
        bookings.flush();

        initialize();

        verify(queueService).initSeatCountIfAbsent(concert.getId(), 9);
    }

    @Test
    void noBookingsInitializesFullCapacity() {
        Concert concert = concert(10, 9, ConcertStatus.OPEN);
        initialize();
        verify(queueService).initSeatCountIfAbsent(concert.getId(), 10);
    }

    @Test
    void overCapacityBlocksNewBookingsAndLogsError(CapturedOutput output) {
        Concert concert = concert(1, 0, ConcertStatus.OPEN);
        booking(concert, 1L, BookingStatus.PENDING_PAYMENT);
        booking(concert, 2L, BookingStatus.CONFIRMED);

        initialize();

        verify(queueService).initSeatCountIfAbsent(concert.getId(), 0);
        assertThat(output.getOut()).contains("ERROR", "[SEAT_INIT][OVER_CAPACITY]",
                "concertId=" + concert.getId(), "totalSeats=1", "activeBookings=2", "fallbackRemaining=0");
    }

    @Test
    void closedConcertIsExcludedAndSoldOutConcertIsIncluded() {
        concert(10, 0, ConcertStatus.CLOSED);
        Concert soldOut = concert(1, 0, ConcertStatus.SOLD_OUT);
        booking(soldOut, 1L, BookingStatus.CONFIRMED);
        initialize();
        verify(queueService).initSeatCountIfAbsent(soldOut.getId(), 0);
        verifyNoMoreInteractions(queueService);
    }

    @Test
    void existingAttemptWithMissingCounterMustNotReinitializeFromBookingCount(CapturedOutput output) {
        Concert concert = concert(10, 0, ConcertStatus.OPEN);
        var attempts = mock(studying.blog.repository.BookingAttemptRepository.class);
        when(attempts.existsByConcertId(concert.getId())).thenReturn(true);
        when(queueService.getRemainingSeat(concert.getId())).thenReturn(null);
        new SeatCountInitializer(attempts, concerts, bookings, queueService).onApplicationEvent(null);
        verify(queueService, never()).initSeatCountIfAbsent(anyLong(), anyInt());
        assertThat(output.getOut()).contains("[SEAT_INIT][BLOCKED]");
    }

    private void initialize() {
        new SeatCountInitializer(mock(studying.blog.repository.BookingAttemptRepository.class), concerts, bookings, queueService).onApplicationEvent(null);
    }

    private Concert concert(int total, int oldCount, ConcertStatus status) {
        return concerts.saveAndFlush(Concert.builder().title("초기화 테스트")
                .eventAt(LocalDateTime.now().plusDays(1)).totalSeats(total)
                .bookedCount(oldCount).status(status).build());
    }

    private Booking booking(Concert concert, Long userId, BookingStatus status) {
        return bookings.saveAndFlush(Booking.builder().concert(concert).userId(userId).status(status).build());
    }
}
