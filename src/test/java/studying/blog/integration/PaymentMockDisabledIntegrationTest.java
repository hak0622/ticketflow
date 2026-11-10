package studying.blog.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import studying.blog.config.TokenProvider;
import studying.blog.domain.*;
import studying.blog.repository.UserRepository;
import studying.blog.service.PaymentService;
import studying.blog.exception.MockPaymentDisabledException;
import studying.blog.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("integration")
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.mock.enabled=false")
class PaymentMockDisabledIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TokenProvider tokens;
    @Autowired PaymentService service;

    @Test void apiAndDirectInvocationCannotConfirmBookingButReadAndTossRouteRemain() throws Exception {
        String id = UUID.randomUUID().toString();
        User user = users.save(User.builder().email(id + "@test.com").nickname(id).role(Role.USER).build());
        Concert concert = savedConcert("Mock disabled", 10, 1);
        Booking booking = savedPendingBooking(concert, user.getId());
        String auth = "Bearer " + tokens.generateToken(user, Duration.ofHours(1));
        mvc.perform(post("/api/concerts/" + concert.getId() + "/payment").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"" + id + "\"}"))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> service.pay(concert.getId(), user.getId(), paymentRequest(id)))
                .isInstanceOf(MockPaymentDisabledException.class);
        assertThat(paymentRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(9);
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        Payment payment = Payment.create(booking, user.getId(), concert.getId(), 90000, "CARD", id);
        payment.complete(); paymentRepository.saveAndFlush(payment);
        mvc.perform(get("/api/concerts/" + concert.getId() + "/payment/me").header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(post("/api/concerts/" + concert.getId() + "/payment/toss-confirm").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"" + id + "\",\"paymentKey\":\"unused\",\"amount\":90000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
