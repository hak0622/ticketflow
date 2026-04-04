package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.Payment;
import studying.blog.domain.PaymentStatus;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class PaymentResponse {
    private Long paymentId;
    private Long bookingId;
    private Long concertId;
    private Long userId;
    private Integer amount;
    private PaymentStatus status;
    private String paymentMethod;
    private String idempotencyKey;
    private LocalDateTime createdAt;
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
