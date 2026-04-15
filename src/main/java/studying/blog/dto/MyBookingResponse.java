package studying.blog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import studying.blog.domain.Booking;
import studying.blog.domain.Concert;

import java.time.LocalDateTime;

@Schema(description = "내 예매 이력 응답")
@Getter
@Builder
@AllArgsConstructor
public class MyBookingResponse {

    @Schema(description = "예매 ID", example = "1")
    private Long bookingId;

    @Schema(description = "공연 ID", example = "1")
    private Long concertId;

    @Schema(description = "공연 제목", example = "아이유 콘서트 2025")
    private String concertTitle;

    @Schema(description = "공연 일시", example = "2025-06-01T18:00:00")
    private LocalDateTime eventAt;

    @Schema(description = "총 좌석 수", example = "500")
    private int totalSeats;

    @Schema(description = "예매된 좌석 수", example = "120")
    private int bookedCount;

    @Schema(description = "공연 상태", example = "OPEN", allowableValues = {"OPEN", "SOLD_OUT", "CLOSED"})
    private String status;

    @Schema(description = "예매 상태", example = "CONFIRMED",
            allowableValues = {"PENDING_PAYMENT", "CONFIRMED", "CANCELLED"})
    private String bookingStatus;

    @Schema(description = "예매 일시", example = "2025-04-11T10:00:00")
    private LocalDateTime bookedAt;

    public static MyBookingResponse from(Booking b){
        return from(b, b.getConcert().getBookedCount());
    }

    public static MyBookingResponse from(Booking b, int bookedCount){
        Concert c = b.getConcert();
        return MyBookingResponse.builder()
                .bookingId(b.getId())
                .concertId(c.getId())
                .concertTitle(c.getTitle())
                .eventAt(c.getEventAt())
                .totalSeats(c.getTotalSeats())
                .bookedCount(bookedCount)
                .status(c.getStatus().name())
                .bookingStatus(b.getStatus().name())
                .bookedAt(b.getCreatedAt())
                .build();
    }
}
