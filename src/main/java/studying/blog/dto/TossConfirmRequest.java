package studying.blog.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TossConfirmRequest {
    private String paymentKey;
    private String orderId;
    private Integer amount;
}