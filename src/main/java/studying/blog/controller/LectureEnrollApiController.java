package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import studying.blog.config.CustomPrincipal;
import studying.blog.dto.EnrollResult;
import studying.blog.dto.EnrollmentResponse;
import studying.blog.service.EnrollService;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/lectures")
public class LectureEnrollApiController {

    private final EnrollService enrollService;

    private Long currentUserId(){
        CustomPrincipal principal = (CustomPrincipal)SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        return principal.getUserId();
    }

    @PostMapping("/{lectureId}/enroll")
    public ResponseEntity<?> enroll(@PathVariable Long lectureId) {
        Long userId = currentUserId();
        EnrollResult result = enrollService.enroll(lectureId, userId);

        return ResponseEntity.ok(Map.of(
                "status", result.getStatus(),
                "lectureId", result.getLectureId(),
                "lectureTitle", result.getLectureTitle()
        ));
    }

    @GetMapping("/{lectureId}/enroll/me")
    public ResponseEntity<?> myEnroll(@PathVariable Long lectureId) {
        Long userId = currentUserId();
        EnrollResult result = enrollService.myEnroll(lectureId, userId);
        return ResponseEntity.ok(Map.of(
                "status", result.getStatus(),
                "lectureId", result.getLectureId(),
                "lectureTitle", result.getLectureTitle()
        ));
    }

    @GetMapping("/{lectureId}/enroll/detail")
    public ResponseEntity<EnrollmentResponse> enrollDetail(@PathVariable Long lectureId) {
        Long userId = currentUserId();

        return ResponseEntity.ok(enrollService.getMyEnrollmentDetail(lectureId, userId));
    }

}
