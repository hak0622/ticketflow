package studying.blog.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import studying.blog.config.TokenProvider;
import studying.blog.domain.OutboxStatus;
import studying.blog.domain.PaymentCompensationOutbox;
import studying.blog.domain.Role;
import studying.blog.domain.User;
import studying.blog.repository.UserRepository;
import studying.blog.service.PaymentCompensationAdminService;
import studying.blog.support.IntegrationTestSupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Tag("integration")
class AdminPaymentCompensationApiIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private PaymentCompensationAdminService adminService;

    @Test
    void ADMIN은_FAILED_Outbox와_실패원인을_조회할_수_있다() throws Exception {
        PaymentCompensationOutbox failed = savedFailedOutbox("PG timeout");
        User admin = savedUser("outbox-admin", Role.ADMIN);

        mockMvc.perform(get("/api/admin/payment-compensations")
                        .param("status", "FAILED")
                        .header("Authorization", authorizationHeader(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outboxId").value(failed.getId()))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].retryCount").value(3))
                .andExpect(jsonPath("$[0].lastError").value("PG timeout"))
                .andExpect(jsonPath("$[0].failedAt").isNotEmpty());
    }

    @Test
    void USER는_Outbox_운영_API에_접근할_수_없다() throws Exception {
        User user = savedUser("outbox-user", Role.USER);

        mockMvc.perform(get("/api/admin/payment-compensations")
                        .header("Authorization", authorizationHeader(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ADMIN이_FAILED_Outbox를_재시도하면_PENDING으로_초기화된다() throws Exception {
        PaymentCompensationOutbox failed = savedFailedOutbox("temporary error");
        User admin = savedUser("retry-admin", Role.ADMIN);

        mockMvc.perform(post("/api/admin/payment-compensations/{outboxId}/retry", failed.getId())
                        .header("Authorization", authorizationHeader(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.lastError").doesNotExist())
                .andExpect(jsonPath("$.failedAt").doesNotExist());

        PaymentCompensationOutbox retried = outboxRepository.findById(failed.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.getRetryCount()).isZero();
        assertThat(retried.getLastError()).isNull();
        assertThat(retried.getFailedAt()).isNull();
    }

    @Test
    void FAILED가_아닌_Outbox는_수동_재시도할_수_없다() throws Exception {
        PaymentCompensationOutbox pending = outboxRepository.save(
                PaymentCompensationOutbox.create("{\"bookingId\":1}"));
        User admin = savedUser("pending-admin", Role.ADMIN);

        mockMvc.perform(post("/api/admin/payment-compensations/{outboxId}/retry", pending.getId())
                        .header("Authorization", authorizationHeader(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void 동일_FAILED_Outbox를_동시에_재시도해도_한_요청만_성공한다() throws Exception {
        PaymentCompensationOutbox failed = savedFailedOutbox("concurrent retry");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        adminService.retry(failed.getId());
                        return true;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();

            int successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) successCount++;
            }

            assertThat(successCount).isEqualTo(1);
            PaymentCompensationOutbox retried = outboxRepository.findById(failed.getId()).orElseThrow();
            assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(retried.getRetryCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private PaymentCompensationOutbox savedFailedOutbox(String errorMessage) {
        PaymentCompensationOutbox outbox = PaymentCompensationOutbox.create(
                "{\"bookingId\":1,\"concertId\":1,\"userId\":1,\"paymentId\":1}");
        outbox.incrementRetry(3, errorMessage);
        outbox.incrementRetry(3, errorMessage);
        outbox.incrementRetry(3, errorMessage);
        return outboxRepository.save(outbox);
    }

    private User savedUser(String prefix, Role role) {
        String unique = prefix + "-" + System.nanoTime();
        return userRepository.save(User.builder()
                .email(unique + "@test.com")
                .password("password")
                .nickname(unique)
                .role(role)
                .build());
    }

    private String authorizationHeader(User user) {
        return "Bearer " + tokenProvider.generateToken(user, Duration.ofHours(1));
    }
}
