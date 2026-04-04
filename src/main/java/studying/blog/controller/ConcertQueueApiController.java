package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import studying.blog.config.CustomPrincipal;
import studying.blog.dto.BookingResult;
import studying.blog.service.BookingService;
import studying.blog.service.QueueService;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class ConcertQueueApiController {
    private final QueueService queueService;
    private final BookingService bookingService;

    private Long currentUserId(){
        CustomPrincipal principal = (CustomPrincipal) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        return principal.getUserId();
    }

    @PostMapping("/{concertId}/queue")
    public ResponseEntity<?> enqueue(@PathVariable Long concertId) {
        Long userId = currentUserId();

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

    //폴링 : 내 순번 조회
    @GetMapping("/{concertId}/queue/me")
    public ResponseEntity<?> myQueue(@PathVariable Long concertId){
        Long userId = currentUserId();

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

        //아직 대기열 안에 있으면 계속 QUEUED 상태
        if (position != null) {
            return ResponseEntity.ok(Map.of(
                    "concertId", concertId,
                    "status", "QUEUED",
                    "position", position,
                    "total", total == null ? 0 : total
            ));
        }

        // 대기열엔 없지만, admitted에 있으면 "진짜 SUCCESS"
        if (queueService.isAdmitted(concertId, userId)) {
            return ResponseEntity.ok(Map.of(
                    "concertId", concertId,
                    "status", "ADMITTED"
            ));
        }

        //둘 다 아니면 "대기열에 없음/만료"
        return ResponseEntity.ok(Map.of(
                "concertId", concertId,
                "status", "NOT_IN_QUEUE",
                "total", total == null ? 0 : total
        ));
    }
}
