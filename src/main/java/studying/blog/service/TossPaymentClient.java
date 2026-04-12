package studying.blog.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Toss Payments API 클라이언트.
 * - connectTimeout / readTimeout : RestClient 레벨에서 처리
 * - Circuit Breaker              : @CircuitBreaker(name = "toss")
 *   CB가 예외를 그대로 전파 → PaymentService.tossConfirm() catch 블록에서 Outbox 생성
 */
@Component
public class TossPaymentClient {

    private static final String CONFIRM_URL = "https://api.tosspayments.com/v1/payments/confirm";
    private static final int TIMEOUT_MS = 3_000;

    @Value("${toss.secret-key}")
    private String secretKey;

    private final RestClient restClient;

    public TossPaymentClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @CircuitBreaker(name = "toss")
    public String confirm(String paymentKey, String orderId, Integer amount) {
        String credentials = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        Map<String, Object> responseBody = restClient.post()
                .uri(CONFIRM_URL)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (responseBody == null) {
            throw new IllegalStateException("Toss 결제 승인 응답이 비어 있습니다.");
        }
        return String.valueOf(responseBody.getOrDefault("method", "TOSS"));
    }
}
