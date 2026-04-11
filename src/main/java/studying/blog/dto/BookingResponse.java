package studying.blog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;

import java.time.LocalDateTime;

@Schema(description = "예매 상세 응답")
@Getter
@AllArgsConstructor
public class BookingResponse {

    @Schema(description = "예매 ID", example = "1")
    private Long bookingId;

    @Schema(description = "공연 ID", example = "1")
    private Long concertId;

    @Schema(description = "공연 제목", example = "아이유 콘서트 2025")
    private String concertTitle;

    @Schema(description = "사용자 ID", example = "42")
    private Long userId;

    @Schema(description = "예매 생성 시각", example = "2025-04-11T10:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "예매 상태", example = "PENDING_PAYMENT",
            allowableValues = {"PENDING_PAYMENT", "CONFIRMED", "CANCELLED"})
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
