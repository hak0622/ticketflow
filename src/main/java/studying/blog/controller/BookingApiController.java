package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import studying.blog.config.CustomPrincipal;
import studying.blog.dto.BookingResult;
import studying.blog.dto.BookingResponse;
import studying.blog.service.BookingService;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class BookingApiController {

    private final BookingService bookingService;

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

    @GetMapping("/{concertId}/booking/detail")
    public ResponseEntity<BookingResponse> bookingDetail(@PathVariable Long concertId,
                                                         @AuthenticationPrincipal CustomPrincipal principal) {
        Long userId = principal.getUserId();

        return ResponseEntity.ok(bookingService.getMyBookingDetail(concertId, userId));
    }
}
