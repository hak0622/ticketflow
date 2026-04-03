package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studying.blog.config.CustomPrincipal;
import studying.blog.dto.CouponIssueResponse;
import studying.blog.service.CouponService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupons")
public class CouponApiController {

    private final CouponService couponService;

    @PostMapping("/{code}/issue")
    public ResponseEntity<CouponIssueResponse> issue(
            @PathVariable String code,
            @AuthenticationPrincipal CustomPrincipal principal) {

        return ResponseEntity.ok(
                CouponIssueResponse.from(couponService.issue(code, principal.getUserId()))
        );
    }
}
