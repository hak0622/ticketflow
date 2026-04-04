package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class BookingResponse {
    private Long bookingId;
    private Long concertId;
    private String concertTitle;
    private Long userId;
    private LocalDateTime createdAt;
    private BookingStatus status;

    public static BookingResponse from(Booking booking){
        return new BookingResponse(
                booking.getId(),
                booking.getConcert().getId(),
                booking.getConcert().getTitle(),
                booking.getUserId(),
                booking.getCreatedAt(),
                booking.getStatus()
        );
    }
}
