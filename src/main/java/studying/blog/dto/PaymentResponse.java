package studying.blog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.Payment;
import studying.blog.domain.PaymentStatus;

import java.time.LocalDateTime;

@Schema(description = "결제 응답")
@Getter
@AllArgsConstructor
public class PaymentResponse {

    @Schema(description = "결제 ID", example = "1")
    private Long paymentId;

    @Schema(description = "예매 ID", example = "1")
    private Long bookingId;

    @Schema(description = "공연 ID", example = "1")
    private Long concertId;

    @Schema(description = "사용자 ID", example = "42")
    private Long userId;

    @Schema(description = "결제 금액 (원)", example = "99000")
    private Integer amount;

    @Schema(description = "결제 상태", example = "COMPLETED", allowableValues = {"PENDING", "COMPLETED", "FAILED"})
    private PaymentStatus status;

    @Schema(description = "결제 수단", example = "MOCK", allowableValues = {"MOCK", "TOSS"})
    private String paymentMethod;

    @Schema(description = "멱등성 키", example = "550e8400-e29b-41d4-a716-446655440000")
    private String idempotencyKey;

    @Schema(description = "결제 생성 시각", example = "2025-04-11T10:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "결제 완료 시각", example = "2025-04-11T10:00:01")
    private LocalDateTime completedAt;

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBooking().getId(),
                payment.getConcertId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getPaymentMethod(),
                payment.getIdempotencyKey(),
                payment.getCreatedAt(),
                payment.getCompletedAt()
        );
    }
}
