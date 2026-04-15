package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Booking;
import studying.blog.dto.MyBookingResponse;
import studying.blog.repository.BookingRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyPageService {
    private final BookingRepository bookingRepository;
    private final QueueService queueService;

    @Transactional(readOnly = true)
    public List<MyBookingResponse> getMyBookings(Long userId){
        return bookingRepository.findByUserIdWithConcert(userId)
                .stream()
                .map(this::toMyBookingResponse)
                .toList();
    }

    private MyBookingResponse toMyBookingResponse(Booking booking) {
        return MyBookingResponse.from(booking, resolveBookedCount(booking));
    }

    private int resolveBookedCount(Booking booking) {
        Long remaining = queueService.getRemainingSeat(booking.getConcert().getId());
        if (remaining == null) {
            org.slf4j.LoggerFactory.getLogger(MyPageService.class)
                    .warn("[BOOKED_COUNT][FALLBACK] concertId={} usingPersistedValue={}",
                            booking.getConcert().getId(), booking.getConcert().getBookedCount());
            return booking.getConcert().getBookedCount();
        }

        long bookedCount = (long) booking.getConcert().getTotalSeats() - remaining;
        if (bookedCount < 0) {
            org.slf4j.LoggerFactory.getLogger(MyPageService.class)
                    .warn("[BOOKED_COUNT][NEGATIVE] concertId={} totalSeats={} remaining={} usingZero",
                            booking.getConcert().getId(), booking.getConcert().getTotalSeats(), remaining);
            return 0;
        }
        if (bookedCount > Integer.MAX_VALUE) {
            return booking.getConcert().getBookedCount();
        }
        return (int) bookedCount;
    }
}
