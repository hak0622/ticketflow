package studying.blog.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
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
 *   호출 예외는 PaymentSaga가 PENDING 재확인 대상으로 보관한다.
 *   최종 실패/취소가 확인됐을 때만 반환 Outbox를 생성한다.
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

    public record PgResult(String paymentKey, String orderId, Integer totalAmount,
                           Integer balanceAmount, String status, String method) {}

    private String auth() {
        return "Basic " + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    @CircuitBreaker(name = "toss")
    public PgResult approve(String paymentKey, String orderId, Integer amount, String idempotencyKey) {
        return restClient.post().uri(CONFIRM_URL)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve().body(PgResult.class);
    }

    @CircuitBreaker(name = "toss")
    public PgResult lookup(String paymentKey) {
        return restClient.get().uri("https://api.tosspayments.com/v1/payments/{key}", paymentKey)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .retrieve().body(PgResult.class);
    }

    @CircuitBreaker(name = "toss")
    public PgResult cancel(String paymentKey, String reason, Integer amount, String idempotencyKey) {
        return restClient.post().uri("https://api.tosspayments.com/v1/payments/{key}/cancel", paymentKey)
                .header(HttpHeaders.AUTHORIZATION, auth())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cancelReason", reason, "cancelAmount", amount))
                .retrieve().body(PgResult.class);
    }
}
