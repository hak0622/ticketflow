package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import studying.blog.config.CustomPrincipal;
import studying.blog.dto.PaymentRequest;
import studying.blog.dto.PaymentResponse;
import studying.blog.dto.TossConfirmRequest;
import studying.blog.service.PaymentService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class PaymentApiController {

    private final PaymentService paymentService;

    private Long currentUserId() {
        CustomPrincipal principal = (CustomPrincipal) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();
        return principal.getUserId();
    }

    @PostMapping("/{concertId}/payment")
    public ResponseEntity<PaymentResponse> pay(
            @PathVariable Long concertId,
            @RequestBody PaymentRequest request) {
        Long userId = currentUserId();
        return ResponseEntity.ok(paymentService.pay(concertId, userId, request));
    }

    @GetMapping("/{concertId}/payment/me")
    public ResponseEntity<PaymentResponse> myPayment(@PathVariable Long concertId) {
        Long userId = currentUserId();
        return ResponseEntity.ok(paymentService.getMyPayment(concertId, userId));
    }

    /** Toss Payments 결제 승인 — 프론트에서 successUrl 콜백 후 호출 */
    @PostMapping("/{concertId}/payment/toss-confirm")
    public ResponseEntity<PaymentResponse> tossConfirm(
            @PathVariable Long concertId,
            @RequestBody TossConfirmRequest request) {
        Long userId = currentUserId();
        return ResponseEntity.ok(paymentService.tossConfirm(concertId, userId, request));
    }
}
