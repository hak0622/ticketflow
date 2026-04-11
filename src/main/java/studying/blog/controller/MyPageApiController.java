package studying.blog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import studying.blog.config.CustomPrincipal;
import studying.blog.dto.MyBookingResponse;
import studying.blog.service.MyPageService;

import java.util.List;

@Tag(name = "MyPage", description = "내 정보 API")
@Controller
@RequiredArgsConstructor
@RequestMapping("/api/me")
public class MyPageApiController {
    private final MyPageService myPageService;

    @Operation(summary = "내 예매 이력 조회", description = "로그인한 사용자의 전체 예매 이력을 최신순으로 반환합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/bookings")
    public ResponseEntity<List<MyBookingResponse>> myBookings(@AuthenticationPrincipal CustomPrincipal principal){
        Long userId = principal.getUserId();
        return ResponseEntity.ok(myPageService.getMyBookings(userId));
    }
}
