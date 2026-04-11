package studying.blog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import studying.blog.config.CustomPrincipal;
import studying.blog.dto.PaymentRequest;
import studying.blog.dto.PaymentResponse;
import studying.blog.dto.TossConfirmRequest;
import studying.blog.service.PaymentService;

@Tag(name = "Payment", description = "결제 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class PaymentApiController {

    private final PaymentService paymentService;

    @Operation(summary = "결제 요청",
            description = "idempotencyKey 기반 멱등성 처리. 동일 키로 중복 요청 시 기존 결과를 반환합니다. " +
                    "Redis SETNX + DB unique key 이중 방어로 중복 결제를 차단합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{concertId}/payment")
    public ResponseEntity<PaymentResponse> pay(
            @PathVariable Long concertId,
            @AuthenticationPrincipal CustomPrincipal principal,
            @RequestBody PaymentRequest request) {
        Long userId = principal.getUserId();
        return ResponseEntity.ok(paymentService.pay(concertId, userId, request));
    }

    @Operation(summary = "내 결제 정보 조회")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{concertId}/payment/me")
    public ResponseEntity<PaymentResponse> myPayment(@PathVariable Long concertId,
                                                     @AuthenticationPrincipal CustomPrincipal principal) {
        Long userId = principal.getUserId();
        return ResponseEntity.ok(paymentService.getMyPayment(concertId, userId));
    }

    @Operation(summary = "Toss 결제 승인",
            description = "프론트엔드 successUrl 콜백 후 Toss 결제를 최종 승인합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{concertId}/payment/toss-confirm")
    public ResponseEntity<PaymentResponse> tossConfirm(
            @PathVariable Long concertId,
            @AuthenticationPrincipal CustomPrincipal principal,
            @RequestBody TossConfirmRequest request) {
        Long userId = principal.getUserId();
        return ResponseEntity.ok(paymentService.tossConfirm(concertId, userId, request));
    }
}
