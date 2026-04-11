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
import studying.blog.service.BookingService;
import studying.blog.service.QueueService;

import java.util.Map;

@Tag(name = "Queue", description = "대기열 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class ConcertQueueApiController {
    private final QueueService queueService;
    private final BookingService bookingService;

    @Operation(summary = "대기열 등록",
            description = "콘서트 대기열에 등록합니다. 이미 예매한 경우 BOOKED, 대기 중이면 QUEUED와 순번을 반환합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{concertId}/queue")
    public ResponseEntity<?> enqueue(@PathVariable Long concertId,
                                     @AuthenticationPrincipal CustomPrincipal principal) {
        Long userId = principal.getUserId();

        BookingResult my = bookingService.myBooking(concertId, userId);
        if("BOOKED".equals(my.getStatus())){
            return ResponseEntity.ok(Map.of(
                    "concertId", concertId,
                    "status", "BOOKED",
                    "message","이미 예매한 콘서트입니다."
            ));
        }

        Long position = queueService.enqueue(concertId, userId);
        Long total = queueService.getTotal(concertId);

        return ResponseEntity.ok(Map.of(
                "concertId", concertId,
                "status", "QUEUED",
                "position", position != null ? position : 0,
                "total", total != null ? total : 0
        ));
    }

    @Operation(summary = "대기 순번 폴링",
            description = "현재 대기 순번을 조회합니다. 입장 가능하면 ADMITTED, 대기 중이면 QUEUED+순번을 반환합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{concertId}/queue/me")
    public ResponseEntity<?> myQueue(@PathVariable Long concertId,
                                     @AuthenticationPrincipal CustomPrincipal principal){
        Long userId = principal.getUserId();

        BookingResult my = bookingService.myBooking(concertId, userId);
        if ("BOOKED".equals(my.getStatus())) {
            return ResponseEntity.ok(Map.of(
                    "concertId", concertId,
                    "status", "BOOKED",
                    "message", "이미 예매한 콘서트입니다."
            ));
        }

        Long position = queueService.getPosition(concertId, userId);
        Long total = queueService.getTotal(concertId);

        if (position != null) {
            return ResponseEntity.ok(Map.of(
                    "concertId", concertId,
                    "status", "QUEUED",
                    "position", position,
                    "total", total == null ? 0 : total
            ));
        }

        if (queueService.isAdmitted(concertId, userId)) {
            return ResponseEntity.ok(Map.of(
                    "concertId", concertId,
                    "status", "ADMITTED"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "concertId", concertId,
                "status", "NOT_IN_QUEUE",
                "total", total == null ? 0 : total
        ));
    }
}
