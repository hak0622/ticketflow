package studying.blog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import studying.blog.domain.OutboxStatus;
import studying.blog.dto.PaymentCompensationAdminResponse;
import studying.blog.service.PaymentCompensationAdminService;

import java.util.List;

@Tag(name = "Admin Payment Compensation", description = "결제 보상 Outbox 운영 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/payment-compensations")
@SecurityRequirement(name = "bearerAuth")
public class AdminPaymentCompensationApiController {

    private final PaymentCompensationAdminService adminService;

    @Operation(summary = "결제 보상 Outbox 상태별 조회")
    @GetMapping
    public ResponseEntity<List<PaymentCompensationAdminResponse>> findByStatus(
            @RequestParam(defaultValue = "FAILED") OutboxStatus status) {
        return ResponseEntity.ok(adminService.findByStatus(status));
    }

    @Operation(summary = "FAILED 결제 보상 Outbox 수동 재시도")
    @PostMapping("/{outboxId}/retry")
    public ResponseEntity<PaymentCompensationAdminResponse> retry(@PathVariable Long outboxId) {
        return ResponseEntity.accepted().body(adminService.retry(outboxId));
    }
}
