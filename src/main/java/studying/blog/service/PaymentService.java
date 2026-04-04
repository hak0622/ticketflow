package studying.blog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.*;
import studying.blog.dto.PaymentRequest;
import studying.blog.dto.PaymentResponse;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.repository.PaymentRepository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final ConcertRepository concertRepository;
    private final StringRedisTemplate redisTemplate;

    private static final int IDEMPOTENCY_TTL_SEC = 30;
    private static final String IDEMPOTENCY_PREFIX = "payment:idempotency:";

    /**
     * 결제 요청.
     *
     * 멱등성 보장 흐름:
     *   1. DB 선조회 — 이미 처리된 idempotencyKey면 캐시된 결과 즉시 반환
     *   2. Redis SETNX — 처리 중 중복 요청 차단 (TTL 30s 안전망)
     *   3. 결제 처리 (Mock PG)
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
        // 예매 조회
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

        // Step 3: Mock PG 처리 — Step 2에서는 항상 성공
        Payment payment = Payment.create(
                booking, userId, concertId,
                concert.getPrice(), request.getPaymentMethod(), iKey
        );
        payment.complete();
        booking.confirm();

        paymentRepository.save(payment);

        log.info("[PAYMENT][COMPLETED] userId={} concertId={} bookingId={} amount={} idempotencyKey={}",
                userId, concertId, booking.getId(), concert.getPrice(), iKey);

        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getMyPayment(Long concertId, Long userId) {
        Booking booking = bookingRepository.findByConcertIdAndUserId(concertId, userId)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역이 없습니다."));

        return paymentRepository.findByBookingId(booking.getId())
                .map(PaymentResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("결제 내역이 없습니다."));
    }
}
