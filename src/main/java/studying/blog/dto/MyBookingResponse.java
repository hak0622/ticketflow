package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import studying.blog.domain.Booking;
import studying.blog.domain.Concert;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class MyBookingResponse {
    private Long bookingId;
    private Long concertId;
    private String concertTitle;
    private LocalDateTime eventAt;
    private int totalSeats;
    private int bookedCount;
    private String status;        // concert status (OPEN / SOLD_OUT / CLOSED)
    private String bookingStatus; // booking status (PENDING_PAYMENT / CONFIRMED / CANCELLED)
    private LocalDateTime bookedAt;

    public static MyBookingResponse from(Booking b){
        Concert c = b.getConcert();
        return MyBookingResponse.builder()
                .bookingId(b.getId())
                .concertId(c.getId())
                .concertTitle(c.getTitle())
                .eventAt(c.getEventAt())
                .totalSeats(c.getTotalSeats())
                .bookedCount(c.getBookedCount())
                .status(c.getStatus().name())
                .bookingStatus(b.getStatus().name())
                .bookedAt(b.getCreatedAt())
                .build();
    }
}
