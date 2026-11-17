package studying.blog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import studying.blog.config.CustomPrincipal;
import studying.blog.service.QueueService;

import java.util.Map;

@Tag(name = "Queue", description = "대기열 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class ConcertQueueApiController {
    private final QueueService queueService;

    @Operation(summary = "대기열 등록",
            description = "콘서트 대기열에 등록합니다. 입장권이 있으면 ADMITTED, 대기 중이면 기존 순번을 유지해 QUEUED를 반환합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{concertId}/queue")
    public ResponseEntity<?> enqueue(@PathVariable Long concertId,
                                     @AuthenticationPrincipal CustomPrincipal principal) {
        Long userId = principal.getUserId();

        QueueService.QueueSnapshot snapshot = queueService.register(concertId, userId);
        if ("ADMITTED".equals(snapshot.status())) {
            return ResponseEntity.ok(Map.of("concertId", concertId, "status", "ADMITTED"));
        }
        return ResponseEntity.ok(Map.of(
                "concertId", concertId,
                "status", snapshot.status(),
                "position", snapshot.position(),
                "total", snapshot.total()
        ));
    }

    @Operation(summary = "대기 순번 폴링",
            description = "현재 대기 순번을 조회합니다. 입장 가능하면 ADMITTED, 대기 중이면 QUEUED+순번을 반환합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{concertId}/queue/me")
    public ResponseEntity<?> myQueue(@PathVariable Long concertId,
                                     @AuthenticationPrincipal CustomPrincipal principal){
        Long userId = principal.getUserId();

        QueueService.QueueSnapshot snapshot = queueService.readQueue(concertId, userId);
        Long position = snapshot.position();
        long total = snapshot.total();

        if (position != null) {
            int nextPollMs = position > 1000 ? 10_000 : 5_000;
            return ResponseEntity.ok(Map.of(
                    "concertId", concertId,
                    "status", "QUEUED",
                    "position", position,
                    "total", total,
                    "nextPollMs", nextPollMs
            ));
        }

        if ("ADMITTED".equals(snapshot.status())) {
            return ResponseEntity.ok(Map.of(
                    "concertId", concertId,
                    "status", "ADMITTED"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "concertId", concertId,
                "status", "NOT_IN_QUEUE",
                "total", total
        ));
    }
}
