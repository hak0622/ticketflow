package studying.blog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import studying.blog.domain.*;
import studying.blog.dto.PaymentRequest;
import studying.blog.dto.PaymentResponse;
import studying.blog.dto.TossConfirmRequest;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.repository.PaymentCompensationOutboxRepository;
import studying.blog.repository.PaymentRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final ConcertRepository concertRepository;
    private final PaymentCompensationOutboxRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${payment.mock.fail-rate:0}")
    private int failRate;

    private static final int IDEMPOTENCY_TTL_SEC = 30;
    private static final String IDEMPOTENCY_PREFIX = "payment:idempotency:";

    /**
     * 결제 요청.
     *
     * 멱등성 보장 흐름:
     *   1. DB 선조회 — 이미 처리된 idempotencyKey면 캐시된 결과 즉시 반환
     *   2. Redis SETNX — 처리 중 중복 요청 차단 (TTL 30s 안전망)
     *   3. 결제 처리 (Mock PG, fail-rate 적용)
     *   4. finally — Redis key 해제
     *
     * DB UNIQUE constraint(uk_payment_idempotency_key)는 Redis 장애 시 최후 방어선.
     */
    @Transactional
    public PaymentResponse pay(Long concertId, Long userId, PaymentRequest request) {
        String iKey = request.getIdempotencyKey();

        // Step 1: DB 선조회
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(iKey);
        if (existing.isPresent()) {
            log.info("[PAYMENT][CACHED] userId={} concertId={} idempotencyKey={} status={}",
                    userId, concertId, iKey, existing.get().getStatus());
            meterRegistry.counter("payment.attempt", "method", "MOCK", "result", "CACHED").increment();
            return PaymentResponse.from(existing.get());
        }

        // Step 2: Redis SETNX — 동시 요청 차단
        String redisKey = IDEMPOTENCY_PREFIX + iKey;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "PROCESSING", Duration.ofSeconds(IDEMPOTENCY_TTL_SEC));

        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("[PAYMENT][PROCESSING] userId={} concertId={} idempotencyKey={}", userId, concertId, iKey);
            throw new IllegalStateException("결제가 처리 중입니다. 잠시 후 다시 시도해주세요.");
        }

        try {
            return doProcess(concertId, userId, request, iKey);
        } finally {
            // Step 4: Redis key 해제 (성공/실패 모두)
            redisTemplate.delete(redisKey);
        }
    }

    private PaymentResponse doProcess(Long concertId, Long userId, PaymentRequest request, String iKey) {
        Booking booking = bookingRepository.findByConcertIdAndUserId(concertId, userId)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역이 없습니다."));

        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("결제할 수 없는 상태입니다. 현재 예매 상태: " + booking.getStatus());
        }

        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new IllegalArgumentException("Concert not found: " + concertId));

        if (concert.getPrice() == null) {
            throw new IllegalStateException("결제 금액이 설정되지 않은 콘서트입니다.");
        }

        // Step 3: Payment 엔티티 생성 (PENDING 상태로 먼저 저장 → paymentId 확보)
        Payment payment = Payment.create(
                booking, userId, concertId,
                concert.getPrice(), request.getPaymentMethod(), iKey
        );
        paymentRepository.save(payment);

        // Step 4: Mock PG 처리 (fail-rate에 따라 성공/실패 결정)
        boolean shouldFail = failRate > 0 && ThreadLocalRandom.current().nextInt(100) < failRate;

        if (shouldFail) {
            payment.fail("Mock PG 실패 (fail-rate=" + failRate + "%)");

            // 결제 실패 보상 이벤트를 같은 트랜잭션에 원자적으로 저장
            String payload = buildPayload(booking.getId(), concertId, userId, payment.getId());
            outboxRepository.save(PaymentCompensationOutbox.create(payload));

            log.warn("[PAYMENT][FAILED] userId={} concertId={} bookingId={} paymentId={} idempotencyKey={}",
                    userId, concertId, booking.getId(), payment.getId(), iKey);

            meterRegistry.counter("payment.attempt", "method", "MOCK", "result", "FAILED").increment();
            return PaymentResponse.from(payment);
        }

        payment.complete();
        booking.confirm();

        log.info("[PAYMENT][COMPLETED] userId={} concertId={} bookingId={} amount={} idempotencyKey={}",
                userId, concertId, booking.getId(), concert.getPrice(), iKey);

        meterRegistry.counter("payment.attempt", "method", "MOCK", "result", "COMPLETED").increment();
        return PaymentResponse.from(payment);
    }

    private String buildPayload(Long bookingId, Long concertId, Long userId, Long paymentId) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("bookingId", bookingId, "concertId", concertId,
                           "userId", userId, "paymentId", paymentId)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패", e);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getMyPayment(Long concertId, Long userId) {
        Booking booking = bookingRepository.findByConcertIdAndUserId(concertId, userId)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역이 없습니다."));

        return paymentRepository.findByBookingId(booking.getId())
                .map(PaymentResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("결제 내역이 없습니다."));
    }

    // ─── Toss Payments 승인 ─────────────────────────────────────────

    private static final String TOSS_CONFIRM_URL = "https://api.tosspayments.com/v1/payments/confirm";
    private final RestClient tossRestClient = RestClient.create();

    @Value("${toss.secret-key}")
    private String tossSecretKey;

    /**
     * Toss Payments 결제 승인.
     * orderId 를 idempotencyKey 로 사용해 중복 승인을 방지한다.
     */
    @Transactional
    public PaymentResponse tossConfirm(Long concertId, Long userId, TossConfirmRequest request) {
        String orderId = request.getOrderId();

        // 1. 멱등성 — 이미 처리된 orderId 면 캐시된 결과 반환
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(orderId);
        if (existing.isPresent()) {
            log.info("[TOSS][CACHED] userId={} concertId={} orderId={} status={}",
                    userId, concertId, orderId, existing.get().getStatus());
            meterRegistry.counter("payment.attempt", "method", "TOSS", "result", "CACHED").increment();
            return PaymentResponse.from(existing.get());
        }

        // 2. 예매 검증
        Booking booking = bookingRepository.findByConcertIdAndUserId(concertId, userId)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역이 없습니다."));

        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("결제할 수 없는 상태입니다. 현재 예매 상태: " + booking.getStatus());
        }

        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new IllegalArgumentException("Concert not found: " + concertId));

        // 3. 금액 위변조 검증
        if (!request.getAmount().equals(concert.getPrice())) {
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다.");
        }

        // 4. Toss 결제 승인 API 호출
        String paymentMethod = callTossConfirmApi(request.getPaymentKey(), orderId, request.getAmount());

        // 5. Payment 엔티티 생성 후 즉시 완료 처리
        Payment payment = Payment.create(booking, userId, concertId,
                request.getAmount(), paymentMethod, orderId);
        paymentRepository.save(payment);
        payment.complete();
        booking.confirm();

        log.info("[TOSS][CONFIRMED] userId={} concertId={} bookingId={} amount={} orderId={}",
                userId, concertId, booking.getId(), request.getAmount(), orderId);

        meterRegistry.counter("payment.attempt", "method", "TOSS", "result", "COMPLETED").increment();
        return PaymentResponse.from(payment);
    }

    /**
     * Toss Payments 서버에 결제 승인 요청을 보낸다.
     * 응답의 method 필드를 반환한다 (결제 수단 기록용).
     */
    private String callTossConfirmApi(String paymentKey, String orderId, Integer amount) {
        String credentials = Base64.getEncoder()
                .encodeToString((tossSecretKey + ":").getBytes(StandardCharsets.UTF_8));

        Map<String, Object> responseBody = tossRestClient.post()
                .uri(TOSS_CONFIRM_URL)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (responseBody == null) {
            throw new IllegalStateException("Toss 결제 승인 응답이 비어 있습니다.");
        }
        return String.valueOf(responseBody.getOrDefault("method", "TOSS"));
    }
}
