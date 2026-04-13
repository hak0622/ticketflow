package studying.blog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;

import java.time.LocalDateTime;

@Schema(description = "공연 응답")
@Getter
@AllArgsConstructor
public class ConcertResponse {

    @Schema(description = "공연 ID", example = "1")
    private Long id;

    @Schema(description = "공연 제목", example = "아이유 콘서트 2025")
    private String title;

    @Schema(description = "공연 일시", example = "2025-06-01T18:00:00")
    private LocalDateTime eventAt;

    @Schema(description = "총 좌석 수", example = "500")
    private int totalSeats;

    @Schema(description = "공연 상태", example = "OPEN", allowableValues = {"OPEN", "SOLD_OUT", "CLOSED"})
    private ConcertStatus status;

    @Schema(description = "예매된 좌석 수", example = "120")
    private int bookedCount;

    @Schema(description = "포스터 이미지 URL", example = "https://example.com/poster.jpg")
    private String posterUrl;

    @Schema(description = "아티스트명", example = "아이유")
    private String artist;

    @Schema(description = "티켓 가격 (원)", example = "99000")
    private Integer price;

    @Schema(description = "장르", example = "K-POP")
    private String genre;

    @Schema(description = "공연장명", example = "잠실올림픽주경기장")
    private String venue;

    @Schema(description = "예매 오픈 일시 (null이면 이미 오픈)", example = "2026-04-15T11:00:00")
    private LocalDateTime bookingOpenAt;

    @Schema(description = "할인율 0~100 (null이면 할인 없음)", example = "25")
    private Integer discountRate;

    public static ConcertResponse from(Concert concert) {
        return new ConcertResponse(
                concert.getId(),
                concert.getTitle(),
                concert.getEventAt(),
                concert.getTotalSeats(),
                concert.getStatus(),
                concert.getBookedCount(),
                concert.getPosterUrl(),
                concert.getArtist(),
                concert.getPrice(),
                concert.getGenre(),
                concert.getVenue(),
                concert.getBookingOpenAt(),
                concert.getDiscountRate()
        );
    }
}
