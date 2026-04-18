package studying.blog.testsupport;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studying.blog.testsupport.dto.TestJwtByEmailRequest;
import studying.blog.testsupport.dto.TestJwtByUserIdRequest;
import studying.blog.testsupport.dto.TestJwtIssueResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/test-support/jwt")
@ConditionalOnProperty(prefix = "app.test-support", name = "enabled", havingValue = "true")
public class TestJwtSupportController {

    private final TestJwtSupportService testJwtSupportService;

    @PostMapping("/by-email")
    public ResponseEntity<TestJwtIssueResponse> issueByEmail(@RequestBody TestJwtByEmailRequest request) {
        return ResponseEntity.ok(testJwtSupportService.issueByEmail(request.getEmail()));
    }

    @PostMapping("/by-user-id")
    public ResponseEntity<TestJwtIssueResponse> issueByUserId(@RequestBody TestJwtByUserIdRequest request) {
        return ResponseEntity.ok(testJwtSupportService.issueByUserId(request.getUserId()));
    }
}
