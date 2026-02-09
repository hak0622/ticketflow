package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import studying.blog.service.QueueService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/lectures")
public class LectureQueueApiController {
    private final QueueService queueService;

    @PostMapping("/{lectureId}/queue")
    public ResponseEntity<?> enqueue(@PathVariable Long lectureId) {

        //지금은 로그인 안하고 대신 UUID로 유저를 구분
        String userKey = UUID.randomUUID().toString();

        Long position = queueService.enqueue(lectureId, userKey);
        Long total = queueService.getTotal(lectureId);

        return ResponseEntity.ok(Map.of(
                "lectureId", lectureId,
                "status", "QUEUED",
                "userKey", userKey, // 프론트에서 저장해야 하므로 추가
                "position", position != null ? position : 0,
                "total", total != null ? total : 0
        ));
    }

    //폴링 : 내 순번 조회
    @GetMapping("/{lectureId}/queue/me")
    public ResponseEntity<?>myQueue(@PathVariable Long lectureId, @RequestParam String userKey){
        Long position = queueService.getPosition(lectureId, userKey);
        Long total = queueService.getTotal(lectureId);

        if (position != null) {
            return ResponseEntity.ok(Map.of(
                    "lectureId", lectureId,
                    "status", "QUEUED",
                    "position", position,
                    "total", total == null ? 0 : total
            ));
        }

        // 대기열엔 없지만, admitted에 있으면 "진짜 SUCCESS"
        if (queueService.isAdmitted(lectureId, userKey)) {
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

