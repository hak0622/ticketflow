package studying.blog.testsupport;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import studying.blog.testsupport.dto.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/test-support")
@ConditionalOnProperty(prefix = "app.test-support", name = "enabled", havingValue = "true")
public class TestJwtSupportController {

    private final TestJwtSupportService testJwtSupportService;
    private final TestConcertSupportService testConcertSupportService;

    @PostMapping("/jwt/by-email")
    public ResponseEntity<TestJwtIssueResponse> issueByEmail(@RequestBody TestJwtByEmailRequest request) {
        return ResponseEntity.ok(testJwtSupportService.issueByEmail(request.getEmail()));
    }

    @PostMapping("/jwt/by-user-id")
    public ResponseEntity<TestJwtIssueResponse> issueByUserId(@RequestBody TestJwtByUserIdRequest request) {
        return ResponseEntity.ok(testJwtSupportService.issueByUserId(request.getUserId()));
    }

    @PostMapping("/concerts/{concertId}/setup")
    public ResponseEntity<TestSetupResponse> setup(
            @PathVariable Long concertId,
            @RequestParam(defaultValue = "QUEUE") TestSetupMode mode,
            @RequestParam(defaultValue = "600") int ttlSeconds) {
        return ResponseEntity.ok(testConcertSupportService.setup(concertId, mode, ttlSeconds));
    }

    @PostMapping("/concerts/{concertId}/reset")
    public ResponseEntity<TestResetResponse> reset(@PathVariable Long concertId) {
        return ResponseEntity.ok(testConcertSupportService.reset(concertId));
    }

    @GetMapping("/csv")
    public ResponseEntity<byte[]> downloadCsv() {
        byte[] content = testJwtSupportService.generateFreshCsvContent().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"booking-users.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(content);
    }
}
