package studying.blog.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import studying.blog.config.TokenProvider;
import studying.blog.domain.*;
import studying.blog.repository.UserRepository;
import studying.blog.service.TossPaymentClient;
import studying.blog.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("integration")
@AutoConfigureMockMvc
class PaymentStageOneIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TokenProvider tokens;
    @MockitoBean TossPaymentClient toss;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void cachedOutcomeIsReusedOnlyForSameOwner(boolean tossApi, boolean failed) throws Exception {
        check(tossApi, failed, "same");
    }

    @ParameterizedTest
    @CsvSource({"false,user", "false,concert", "false,both", "true,user", "true,concert", "true,both"})
    void foreignKeyReturns400WithoutSideEffects(boolean tossApi, String mismatch) throws Exception {
        check(tossApi, false, mismatch);
    }

    private User user() {
        String id = UUID.randomUUID().toString();
        return users.save(User.builder().email(id + "@test.com").nickname(id).role(Role.USER).build());
    }

    private void check(boolean tossApi, boolean failed, String mismatch) throws Exception {
        User owner = user();
        User caller = mismatch.equals("user") || mismatch.equals("both") ? user() : owner;
        Concert concert = savedConcert("Payment owner", 10, 1);
        Concert target = mismatch.equals("concert") || mismatch.equals("both")
                ? savedConcert("Different concert", 10, 0) : concert;
        Booking booking = savedPendingBooking(concert, owner.getId());
        String key = UUID.randomUUID().toString();
        Payment payment = Payment.create(booking, owner.getId(), concert.getId(), 90000, "CARD", key);
        if (failed) payment.fail("fixture failure"); else payment.complete();
        paymentRepository.saveAndFlush(payment);
        Long remaining = queueService.getRemainingSeat(concert.getId());
        var marker = redisTemplate.opsForHash().entries("booking:attempt:" + attemptRepository.findByBookingId(booking.getId()).orElseThrow().getId());
        String body = tossApi
                ? "{\"orderId\":\"" + key + "\",\"paymentKey\":\"unused\",\"amount\":90000}"
                : "{\"idempotencyKey\":\"" + key + "\",\"paymentMethod\":\"CARD\"}";
        var response = mvc.perform(post("/api/concerts/" + target.getId() + (tossApi ? "/payment/toss-confirm" : "/payment"))
                .header("Authorization", "Bearer " + tokens.generateToken(caller, Duration.ofHours(1)))
                .contentType(MediaType.APPLICATION_JSON).content(body));
        if (mismatch.equals("same")) {
            response.andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(payment.getId()))
                    .andExpect(jsonPath("$.status").value(failed ? "FAILED" : "COMPLETED"));
        } else {
            response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.paymentId").doesNotExist())
                    .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.amount").doesNotExist());
        }
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isZero();
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(remaining);
        assertThat(redisTemplate.opsForHash().entries("booking:attempt:" + attemptRepository.findByBookingId(booking.getId()).orElseThrow().getId())).isEqualTo(marker);
        assertThat(redisTemplate.keys("payment:idempotency:*")).isEmpty();
        verifyNoInteractions(toss);
    }
}
