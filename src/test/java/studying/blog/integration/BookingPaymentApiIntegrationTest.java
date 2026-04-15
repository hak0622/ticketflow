package studying.blog.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import studying.blog.config.TokenProvider;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingStatus;
import studying.blog.domain.Concert;
import studying.blog.domain.Payment;
import studying.blog.domain.PaymentStatus;
import studying.blog.domain.Role;
import studying.blog.domain.User;
import studying.blog.dto.PaymentRequest;
import studying.blog.repository.UserRepository;
import studying.blog.support.IntegrationTestSupport;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Tag("integration")
class BookingPaymentApiIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenProvider tokenProvider;

    @Test
    void shouldReturn200AndCreateBookingWhenAdmittedUserCallsBookingApi() throws Exception {
        // Given
        User user = savedUser("booking-admitted", Role.USER);
        Concert concert = savedConcert("API Booking Concert", 10, 0);
        grantAdmitted(concert.getId(), user.getId());

        // When / Then
        mockMvc.perform(post("/api/concerts/{concertId}/booking", concert.getId())
                        .header("Authorization", authorizationHeader(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.concertId").value(concert.getId()))
                .andExpect(jsonPath("$.concertTitle").value("API Booking Concert"));

        List<Booking> bookings = bookingRepository.findAllByConcertId(concert.getId());
        Long remaining = queueService.getRemainingSeat(concert.getId());

        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getUserId()).isEqualTo(user.getId());
        assertThat(bookings.get(0).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(remaining).isEqualTo(9L);
    }

    @Test
    void shouldReturn409AndKeepDatabaseUnchangedWhenWaitingUserCallsBookingApi() throws Exception {
        // Given
        User user = savedUser("booking-waiting", Role.USER);
        Concert concert = savedConcert("API Waiting Concert", 10, 0);

        // When / Then
        mockMvc.perform(post("/api/concerts/{concertId}/booking", concert.getId())
                        .header("Authorization", authorizationHeader(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isNotEmpty());

        List<Booking> bookings = bookingRepository.findAllByConcertId(concert.getId());
        Long remaining = queueService.getRemainingSeat(concert.getId());

        assertThat(bookings).isEmpty();
        assertThat(remaining).isEqualTo(10L);
    }

    @Test
    void shouldReturn200AndCompletePaymentWhenUserCallsPaymentApi() throws Exception {
        // Given
        User user = savedUser("payment-success", Role.USER);
        Concert concert = savedConcert("API Payment Concert", 10, 1);
        Booking booking = savedPendingBooking(concert, user.getId());
        String idempotencyKey = UUID.randomUUID().toString();

        // When / Then
        mockMvc.perform(post("/api/concerts/{concertId}/payment", concert.getId())
                        .header("Authorization", authorizationHeader(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest(idempotencyKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").isNumber())
                .andExpect(jsonPath("$.bookingId").value(booking.getId()))
                .andExpect(jsonPath("$.concertId").value(concert.getId()))
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.paymentMethod").value("CARD"))
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey));

        Payment payment = paymentRepository.findByBookingId(booking.getId()).orElseThrow();
        Booking reloadedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        Long remaining = queueService.getRemainingSeat(concert.getId());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(remaining).isEqualTo(9L);
    }

    @Test
    void shouldReturnCachedPaymentAndKeepSingleRowWhenPaymentApiIsRetriedWithSameIdempotencyKey() throws Exception {
        // Given
        User user = savedUser("payment-idempotency", Role.USER);
        Concert concert = savedConcert("API Payment Idempotency Concert", 10, 1);
        Booking booking = savedPendingBooking(concert, user.getId());
        String idempotencyKey = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(paymentRequest(idempotencyKey));

        // When / Then
        String firstResponse = mockMvc.perform(post("/api/concerts/{concertId}/payment", concert.getId())
                        .header("Authorization", authorizationHeader(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(booking.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String secondResponse = mockMvc.perform(post("/api/concerts/{concertId}/payment", concert.getId())
                        .header("Authorization", authorizationHeader(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(booking.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long firstPaymentId = objectMapper.readTree(firstResponse).get("paymentId").asLong();
        Long secondPaymentId = objectMapper.readTree(secondResponse).get("paymentId").asLong();

        Payment payment = paymentRepository.findByBookingId(booking.getId()).orElseThrow();
        Booking reloadedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        Long remaining = queueService.getRemainingSeat(concert.getId());

        assertThat(firstPaymentId).isEqualTo(secondPaymentId);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(remaining).isEqualTo(9L);
    }

    @Test
    void shouldReturn403WhenUserRoleCallsAdminConcertApi() throws Exception {
        // Given
        User user = savedUser("admin-forbidden", Role.USER);
        long bookingCountBefore = bookingRepository.count();
        long paymentCountBefore = paymentRepository.count();
        long concertCountBefore = concertRepository.count();

        // When / Then
        mockMvc.perform(get("/api/admin/concerts")
                        .header("Authorization", authorizationHeader(user)))
                .andExpect(status().isForbidden());

        assertThat(bookingRepository.count()).isEqualTo(bookingCountBefore);
        assertThat(paymentRepository.count()).isEqualTo(paymentCountBefore);
        assertThat(concertRepository.count()).isEqualTo(concertCountBefore);
    }

    private User savedUser(String prefix, Role role) {
        String uniq = prefix + "-" + System.nanoTime();
        return userRepository.save(User.builder()
                .email(uniq + "@test.com")
                .password("password")
                .nickname(uniq)
                .role(role)
                .build());
    }

    private String authorizationHeader(User user) {
        return "Bearer " + tokenProvider.generateToken(user, Duration.ofHours(1));
    }
}
