package studying.blog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import studying.blog.config.CustomPrincipal;
import studying.blog.dto.BookingResult;
import studying.blog.dto.BookingResponse;
import studying.blog.service.BookingService;

import java.util.Map;

@Tag(name = "Booking", description = "예매 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class BookingApiController {

    private final BookingService bookingService;

    @Operation(summary = "예매 요청",
            description = "대기열 입장 권한(ADMITTED) 보유 사용자가 예매를 요청합니다. Pessimistic Lock으로 동시성을 제어합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{concertId}/booking")
    public ResponseEntity<?> book(@PathVariable Long concertId,
                                  @AuthenticationPrincipal CustomPrincipal principal) {
        Long userId = principal.getUserId();
        BookingResult result = bookingService.book(concertId, userId);

        return ResponseEntity.ok(Map.of(
                "status", result.getStatus(),
                "concertId", result.getConcertId(),
                "concertTitle", result.getConcertTitle()
        ));
    }

    @Operation(summary = "내 예매 상태 조회")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{concertId}/booking/me")
    public ResponseEntity<?> myBooking(@PathVariable Long concertId,
                                       @AuthenticationPrincipal CustomPrincipal principal) {
        Long userId = principal.getUserId();
        BookingResult result = bookingService.myBooking(concertId, userId);
        return ResponseEntity.ok(Map.of(
                "status", result.getStatus(),
                "concertId", result.getConcertId(),
                "concertTitle", result.getConcertTitle()
        ));
    }

    @Operation(summary = "예매 상세 조회")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{concertId}/booking/detail")
    public ResponseEntity<BookingResponse> bookingDetail(@PathVariable Long concertId,
                                                         @AuthenticationPrincipal CustomPrincipal principal) {
        Long userId = principal.getUserId();
        return ResponseEntity.ok(bookingService.getMyBookingDetail(concertId, userId));
    }
}
