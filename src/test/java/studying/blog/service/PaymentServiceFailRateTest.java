package studying.blog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import studying.blog.domain.*;
import studying.blog.dto.PaymentRequest;
import studying.blog.dto.PaymentResponse;
import studying.blog.repository.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceFailRateTest {

    @Autowired private PaymentService paymentService;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentCompensationOutboxRepository outboxRepository;

    @MockBean private StringRedisTemplate redisTemplate;

    private Concert concert;
    private Booking booking;
    private static final Long USER_ID = 1L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
        bookingRepository.deleteAll();
        concertRepository.deleteAll();

        concert = concertRepository.save(Concert.builder()
                .title("테스트 콘서트")
                .totalSeats(10)
                .bookedCount(1)
                .status(ConcertStatus.OPEN)
                .eventAt(LocalDateTime.now().minusMinutes(1))
                .price(90000)
                .build());

        booking = bookingRepository.save(Booking.builder()
                .concert(concert)
                .userId(USER_ID)
                .build());

        // Redis SETNX 항상 획득 성공으로 mock
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn((ValueOperations) valueOps);
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        given(redisTemplate.delete(anyString())).willReturn(true);

        ReflectionTestUtils.setField(paymentService, "failRate", 0);
    }

    @Test
    void fail_rate_0이면_결제_성공_Booking_CONFIRMED() {
        PaymentResponse response = paymentService.pay(concert.getId(), USER_ID, request());

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(response.getAmount()).isEqualTo(90000);
        assertThat(outboxRepository.count()).isZero();

        Booking updated = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void fail_rate_100이면_결제_실패_Outbox_PENDING_저장() {
        ReflectionTestUtils.setField(paymentService, "failRate", 100);

        PaymentResponse response = paymentService.pay(concert.getId(), USER_ID, request());

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(outboxRepository.count()).isEqualTo(1);

        PaymentCompensationOutbox outbox = outboxRepository.findAll().get(0);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getEventType()).isEqualTo("payment.compensation");
    }

    @Test
    void fail_rate_100이면_Booking은_보상_전_PENDING_PAYMENT_유지() {
        ReflectionTestUtils.setField(paymentService, "failRate", 100);

        paymentService.pay(concert.getId(), USER_ID, request());

        Booking updated = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void 동일_idempotencyKey_두번_요청시_Payment_1건만_생성() {
        String iKey = UUID.randomUUID().toString();

        paymentService.pay(concert.getId(), USER_ID, request(iKey));
        paymentService.pay(concert.getId(), USER_ID, request(iKey));  // 같은 key 재요청

        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void 동일_idempotencyKey_재요청시_기존_결과_반환() {
        String iKey = UUID.randomUUID().toString();

        PaymentResponse first  = paymentService.pay(concert.getId(), USER_ID, request(iKey));
        PaymentResponse second = paymentService.pay(concert.getId(), USER_ID, request(iKey));

        assertThat(second.getPaymentId()).isEqualTo(first.getPaymentId());
        assertThat(second.getStatus()).isEqualTo(first.getStatus());
    }

    @Test
    void 이미_CONFIRMED된_Booking에_다른_key로_재결제시_예외() {
        // 첫 결제 성공 → Booking CONFIRMED
        paymentService.pay(concert.getId(), USER_ID, request());

        // 두 번째 결제 시도 (다른 idempotencyKey)
        assertThatThrownBy(() ->
                paymentService.pay(concert.getId(), USER_ID, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("결제할 수 없는 상태");
    }

    // --- helpers ---

    private PaymentRequest request() {
        return request(UUID.randomUUID().toString());
    }

    private PaymentRequest request(String iKey) {
        PaymentRequest req = new PaymentRequest();
        ReflectionTestUtils.setField(req, "idempotencyKey", iKey);
        ReflectionTestUtils.setField(req, "paymentMethod", "CARD");
        return req;
    }
}
