package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private Long currentUserId(){
        CustomPrincipal principal = (CustomPrincipal)SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        return principal.getUserId();
    }

    @PostMapping("/{concertId}/booking")
    public ResponseEntity<?> book(@PathVariable Long concertId) {
        Long userId = currentUserId();
        BookingResult result = bookingService.book(concertId, userId);

        return ResponseEntity.ok(Map.of(
                "status", result.getStatus(),
                "concertId", result.getConcertId(),
                "concertTitle", result.getConcertTitle()
        ));
    }

    @GetMapping("/{concertId}/booking/me")
    public ResponseEntity<?> myBooking(@PathVariable Long concertId) {
        Long userId = currentUserId();
        BookingResult result = bookingService.myBooking(concertId, userId);
        return ResponseEntity.ok(Map.of(
                "status", result.getStatus(),
                "concertId", result.getConcertId(),
                "concertTitle", result.getConcertTitle()
        ));
    }

    @GetMapping("/{concertId}/booking/detail")
    public ResponseEntity<BookingResponse> bookingDetail(@PathVariable Long concertId) {
        Long userId = currentUserId();

        return ResponseEntity.ok(bookingService.getMyBookingDetail(concertId, userId));
    }
}
