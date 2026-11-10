package studying.blog.fault;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.*;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import studying.blog.domain.*;
import studying.blog.dto.TossConfirmRequest;
import studying.blog.scheduler.BookingExpiryScheduler;
import studying.blog.scheduler.PaymentCompensationOutboxProcessor;
import studying.blog.service.*;
import studying.blog.support.IntegrationTestSupport;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** PASS means the existing unsafe behavior was reproduced, not that recovery is safe. */
@Tag("integration")
@Disabled("Historical unsafe baseline; see PaymentSagaIntegrationTest for current recovery assertions")
class PaymentPgBoundaryCharacterizationTest extends IntegrationTestSupport {
    @Autowired PaymentService payments;
    @Autowired BookingService bookings;
    @Autowired TossPaymentClient pg;
    @Autowired PaymentCompensationOutboxProcessor compensation;
    @Autowired BookingExpiryScheduler expiry;
    @Autowired ObjectMapper json;
    HttpServer server;
    ExecutorService executor;
    Object pgTarget;
    RestClient original;
    final ConcurrentMap<String, String> approved = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean loseReply;

    @BeforeEach void localPg() throws Exception {
        // Only the disposable Testcontainers DB: allow this test user to install fault triggers.
        var mysql = (org.testcontainers.containers.MySQLContainer<?>) ReflectionTestUtils.getField(IntegrationTestSupport.class, "MYSQL");
        var setup = mysql.execInContainer("mysql", "-uroot", "-ptest", "-e", "SET GLOBAL log_bin_trust_function_creators=1");
        assertThat(setup.getExitCode()).as("fault trigger setup in disposable MySQL").isZero();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/confirm", exchange -> {
            var body = json.readTree(exchange.getRequestBody());
            String order = body.get("orderId").asText();
            approved.put(order, "APPROVED");
            calls.incrementAndGet();
            // The separate fake PG has already approved before withholding its HTTP reply.
            if (loseReply) {
                try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] response = "{\"method\":\"CARD\"}".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (java.io.IOException expectedLostReply) {
                // Deliberate client read timeout.
            } finally { exchange.close(); }
        });
        server.start();
        URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/confirm");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        var client = RestClient.builder().requestFactory(factory)
                .requestInterceptor((request, body, execution) -> execution.execute(new HttpRequestWrapper(request) {
                    @Override public URI getURI() { return local; }
                }, body)).build();
        pgTarget = AopTestUtils.getUltimateTargetObject(pg);
        original = (RestClient) ReflectionTestUtils.getField(pgTarget, "restClient");
        ReflectionTestUtils.setField(pgTarget, "restClient", client);
    }

    @AfterEach void cleanup() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fault_pg_payment_update");
        if (original != null) ReflectionTestUtils.setField(pgTarget, "restClient", original);
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test void approvedButHttpReplyLostIsMarkedFailedThenCompensated() throws Exception {
        var concert = savedConcert("PG reply loss", 3, 0);
        grantAdmitted(concert.getId(), 1L);
        String requestId = UUID.randomUUID().toString();
        bookings.book(concert.getId(), 1L, requestId);
        String order = UUID.randomUUID().toString();
        loseReply = true;
        var response = payments.tossConfirm(concert.getId(), 1L, request(order));
        assertThat(approved).containsEntry(order, "APPROVED");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(2);
        assertThat(outboxRepository.count()).isEqualTo(1);
        compensation.process(outboxRepository.findAll().get(0).getId());
        assertReturned(concert.getId(), requestId);
        assertThat(payments.tossConfirm(concert.getId(), 1L, request(order)).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(calls.get()).isEqualTo(1); // Existing FAILED is reused without checking PG.
        assertThat(approved.get(order)).isEqualTo("APPROVED");
        System.out.println("[PG_BOUNDARY] reply_loss: PG=APPROVED Payment=FAILED Booking=CANCELLED Attempt=RELEASED remaining=3 cachedRetry=FAILED");
    }

    @Test void approvedThenMysqlUpdateFailsLosesPaymentAndExpiryReturnsTicket() throws Exception {
        var concert = savedConcert("PG DB failure", 3, 0);
        grantAdmitted(concert.getId(), 1L);
        String requestId = UUID.randomUUID().toString();
        bookings.book(concert.getId(), 1L, requestId);
        String order = UUID.randomUUID().toString();
        jdbcTemplate.execute("CREATE TRIGGER fault_pg_payment_update BEFORE UPDATE ON payment FOR EACH ROW "
                + "BEGIN IF NEW.status = 'COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'PG approved DB update rejected'; END IF; END");
        assertThatThrownBy(() -> payments.tossConfirm(concert.getId(), 1L, request(order))).isInstanceOf(RuntimeException.class);
        assertThat(approved).containsEntry(order, "APPROVED");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(paymentRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(bookingRepository.findByConcertIdAndUserId(concert.getId(), 1L).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(attemptRepository.findById(requestId).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(2);
        jdbcTemplate.execute("DROP TRIGGER fault_pg_payment_update");
        // Advance only the test Booking age; invoke the real expiry method deterministically.
        jdbcTemplate.update("UPDATE booking SET created_at=? WHERE concert_id=?", LocalDateTime.now().minusMinutes(31), concert.getId());
        expiry.expireStaleBookings();
        assertReturned(concert.getId(), requestId);
        assertThat(approved.get(order)).isEqualTo("APPROVED");
        System.out.println("[PG_BOUNDARY] db_failure: PG=APPROVED Payment=NONE Outbox=NONE Booking=CANCELLED Attempt=RELEASED remaining=3");
    }

    private TossConfirmRequest request(String order) throws Exception {
        return json.readValue("{\"paymentKey\":\"local-only\",\"orderId\":\"" + order + "\",\"amount\":90000}", TossConfirmRequest.class);
    }
    private void assertReturned(Long cid, String requestId) {
        assertThat(bookingRepository.findByConcertIdAndUserId(cid, 1L).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(attemptRepository.findById(requestId).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
        assertThat(queueService.getRemainingSeat(cid)).isEqualTo(3);
        assertThat(redisTemplate.opsForHash().get("booking:attempt:" + requestId, "releaseOutcome")).isEqualTo("RETURNED");
    }
}
