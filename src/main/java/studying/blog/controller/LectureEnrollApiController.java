package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import studying.blog.dto.EnrollmentResponse;
import studying.blog.service.EnrollService;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/lectures")
public class LectureEnrollApiController {

    private final EnrollService enrollService;

    @PostMapping("/{lectureId}/enroll")
    public ResponseEntity<?> enroll(@PathVariable Long lectureId, @RequestParam String userKey) {
        var result = enrollService.enroll(lectureId, userKey);
        return ResponseEntity.ok(Map.of(
                "status", result.status(),
                "lectureId", result.lectureId(),
                "lectureTitle", result.lectureTitle()
        ));
    }

    @GetMapping("/{lectureId}/enroll/me")
    public ResponseEntity<?> myEnroll(@PathVariable Long lectureId, @RequestParam String userKey) {
        var result = enrollService.myEnroll(lectureId, userKey);
        return ResponseEntity.ok(Map.of(
                "status", result.status(),
                "lectureId", result.lectureId(),
                "lectureTitle", result.lectureTitle()
        ));
    }

    @GetMapping("/{lectureId}/enroll/detail")
    public ResponseEntity<EnrollmentResponse> enrollDetail(@PathVariable Long lectureId,
                                                           @RequestParam String userKey) {
        return ResponseEntity.ok(enrollService.getMyEnrollmentDetail(lectureId, userKey));
    }

}
