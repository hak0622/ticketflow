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
            description = "대기열 입장 권한(ADMITTED) 보유 사용자가 예매를 요청합니다. 요청 ID별 Redis Lua와 DB 요청 기록으로 중복 처리를 방지합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{concertId}/booking")
    public ResponseEntity<?> book(@PathVariable Long concertId,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String requestId,
                                  @AuthenticationPrincipal CustomPrincipal principal) {
        Long userId = principal.getUserId();
        String id = requestId == null ? java.util.UUID.randomUUID().toString() : java.util.UUID.fromString(requestId).toString();
        BookingResult result = bookingService.book(concertId, userId, id);

        int status = "BOOKING_PROCESSING".equals(result.getStatus()) ? 202
                : "BOOKING_FAILED".equals(result.getStatus()) ? 409 : 200;
        return ResponseEntity.status(status).header("Idempotency-Key", id).body(Map.of(
                "status", result.getStatus(), "requestId", id,
                "concertId", result.getConcertId(), "concertTitle", result.getConcertTitle(),
                "message", status == 409 ? "종료된 예매 요청입니다. 새 요청 ID로 다시 시도해주세요." : ""
        ));
    }

    @GetMapping("/{concertId}/booking/attempts/{requestId}")
    public ResponseEntity<?> attemptStatus(@PathVariable Long concertId, @PathVariable String requestId,
            @AuthenticationPrincipal CustomPrincipal principal) {
        var data = bookingService.attemptStatus(concertId, principal.getUserId(), requestId);
        int code = "BOOKING_PROCESSING".equals(data.get("status")) ? 202
                : "BOOKING_FAILED".equals(data.get("status")) ? 409 : 200;
        return ResponseEntity.status(code).body(data);
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
