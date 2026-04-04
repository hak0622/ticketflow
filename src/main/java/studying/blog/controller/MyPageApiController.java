package studying.blog.controller;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import studying.blog.config.CustomPrincipal;
import studying.blog.dto.MyBookingResponse;
import studying.blog.service.MyPageService;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api/me")
public class MyPageApiController {
    private final MyPageService myPageService;

    private Long currentUserId(){
        CustomPrincipal principal = (CustomPrincipal) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();
        return principal.getUserId();
    }

    @GetMapping("/bookings")
    public ResponseEntity<List<MyBookingResponse>> myBookings(){
        Long userId = currentUserId();
        return ResponseEntity.ok(myPageService.getMyBookings(userId));
    }
}
