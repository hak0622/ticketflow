package studying.blog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "결제 요청")
@Getter
@NoArgsConstructor
public class PaymentRequest {

    @Schema(description = "멱등성 키 — 동일 값으로 재요청 시 중복 결제 방지", example = "550e8400-e29b-41d4-a716-446655440000")
    private String idempotencyKey;

    @Schema(description = "결제 수단", example = "MOCK", allowableValues = {"MOCK", "TOSS"})
    private String paymentMethod;
}
