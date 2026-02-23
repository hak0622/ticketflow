package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import studying.blog.config.CustomPrincipal;
import studying.blog.service.QueueService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/lectures")
public class LectureQueueApiController {
    private final QueueService queueService;

    private Long currentUserId(){
        CustomPrincipal principal = (CustomPrincipal) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        return principal.getUserId();
    }

    @PostMapping("/{lectureId}/queue")
    public ResponseEntity<?> enqueue(@PathVariable Long lectureId) {
        Long userId = currentUserId();

        Long position = queueService.enqueue(lectureId, userId);
        Long total = queueService.getTotal(lectureId);

        return ResponseEntity.ok(Map.of(
                "lectureId", lectureId,
                "status", "QUEUED",
                "position", position != null ? position : 0,
                "total", total != null ? total : 0
        ));
    }

    //폴링 : 내 순번 조회
    @GetMapping("/{lectureId}/queue/me")
    public ResponseEntity<?>myQueue(@PathVariable Long lectureId){
        Long userId = currentUserId();

        Long position = queueService.getPosition(lectureId, userId);
        Long total = queueService.getTotal(lectureId);

        //아직 대기열 안에 있으면 계속 QUEUED 상태
        if (position != null) {
            return ResponseEntity.ok(Map.of(
                    "lectureId", lectureId,
                    "status", "QUEUED",
                    "position", position,
                    "total", total == null ? 0 : total
            ));
        }

        // 대기열엔 없지만, admitted에 있으면 "진짜 SUCCESS"
        if (queueService.isAdmitted(lectureId, userId)) {
            return ResponseEntity.ok(Map.of(
                    "lectureId", lectureId,
                    "status", "SUCCESS"
            ));
        }

        //둘 다 아니면 성공이 아니라 "대기열에 없음/만료"
        return ResponseEntity.ok(Map.of(
                "lectureId", lectureId,
                "status", "NOT_IN_QUEUE",
                "total", total == null ? 0 : total
        ));
    }
}

