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
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Real MySQL/Redis and HTTP PG boundary recovery. */
@Tag("integration")

class PaymentSagaIntegrationTest extends IntegrationTestSupport {
    @Autowired PaymentService payments;
    @Autowired BookingService bookings;
    @Autowired TossPaymentClient pg;
    @Autowired PaymentCompensationOutboxProcessor compensation;
    @Autowired BookingExpiryScheduler expiry;
    @Autowired ObjectMapper json;
    @Autowired PaymentSaga saga;
    @Autowired studying.blog.scheduler.PaymentRecoveryWorker worker;
    @Autowired io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry breakers;
    String activeOrder;
    HttpServer server;
    ExecutorService executor;
    Object pgTarget;
    RestClient original;
    final ConcurrentMap<String, String> approved = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean loseReply;
    volatile boolean loseCancel;
    volatile boolean queryFails;
    volatile boolean canceled;
    volatile String pgStatus="DONE";
    volatile String pgMethod = "CARD";
    volatile String pgPaymentKey = "local-only";
    volatile String pgOrderOverride;
    volatile int pgAmount = 90000;
    final AtomicInteger cancelCalls=new AtomicInteger();

    @BeforeEach void localPg() throws Exception {
        breakers.circuitBreaker("toss").reset();
        // Only the disposable Testcontainers DB: allow this test user to install fault triggers.
        var mysql = (org.testcontainers.containers.MySQLContainer<?>) ReflectionTestUtils.getField(IntegrationTestSupport.class, "MYSQL");
        var setup = mysql.execInContainer("mysql", "-uroot", "-ptest", "-e", "SET GLOBAL log_bin_trust_function_creators=1");
        assertThat(setup.getExitCode()).as("fault trigger setup in disposable MySQL").isZero();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            boolean post=exchange.getRequestMethod().equals("POST");
            boolean cancel=exchange.getRequestURI().getPath().endsWith("/cancel");
            if(!post && queryFails) { exchange.sendResponseHeaders(503,-1); exchange.close(); return; }
            String order=activeOrder;
            if(post && !cancel) {
                var body=json.readTree(exchange.getRequestBody()); order=body.get("orderId").asText();
                activeOrder=order; approved.put(order,"APPROVED"); calls.incrementAndGet();
            }
            if(cancel) { canceled=true; cancelCalls.incrementAndGet(); }
            // The separate fake PG has already approved before withholding its HTTP reply.
            if ((loseReply && post && !cancel) || (loseCancel && cancel)) {
                try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] response = json.writeValueAsBytes(java.util.Map.of(
                    "method", pgMethod, "paymentKey", pgPaymentKey,
                    "orderId", pgOrderOverride == null ? order : pgOrderOverride,
                    "totalAmount", pgAmount, "balanceAmount", canceled ? 0 : pgAmount,
                    "status", canceled ? "CANCELED" : pgStatus));
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
                    @Override public URI getURI() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+request.getURI().getPath()); }
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

    @Test void approvedReplyLossIsReconciledWithoutCompensation() throws Exception {
        var concert = savedConcert("PG reply loss", 3, 0);
        grantAdmitted(concert.getId(), 1L);
        String requestId = UUID.randomUUID().toString();
        bookings.book(concert.getId(), 1L, requestId);
        String order = UUID.randomUUID().toString();
        loseReply = true;
        var response = payments.tossConfirm(concert.getId(), 1L, request(order));
        assertThat(approved).containsEntry(order, "APPROVED");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outboxRepository.count()).isZero();
        jdbcTemplate.update("UPDATE booking SET created_at=? WHERE concert_id=?",LocalDateTime.now().minusMinutes(31),concert.getId());
        expiry.expireStaleBookings();
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(2);
        loseReply=false;
        saga.recover(response.getPaymentId());
        assertConfirmed(concert.getId(),requestId);
        assertThat(payments.tossConfirm(concert.getId(),1L,request(order)).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void approvedThenDbFailureRecoversWithoutReturningTicket() throws Exception {
        var concert=savedConcert("PG DB failure",3,0); grantAdmitted(concert.getId(),1L);
        String requestId=UUID.randomUUID().toString(); bookings.book(concert.getId(),1L,requestId);
        String order=UUID.randomUUID().toString();
        jdbcTemplate.execute("CREATE TRIGGER fault_pg_payment_update BEFORE UPDATE ON payment FOR EACH ROW BEGIN IF NEW.status = 'COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'PG approved DB update rejected'; END IF; END");
        var response=payments.tossConfirm(concert.getId(),1L,request(order));
        assertThat(approved).containsEntry(order,"APPROVED");
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isZero();
        jdbcTemplate.execute("DROP TRIGGER fault_pg_payment_update");
        saga.recover(response.getPaymentId());
        assertConfirmed(concert.getId(),requestId);
    }
    @Test void cancellationReplyLossThenQueryRecoversAndReturnsOnce() throws Exception {
        var c=savedConcert("cancel reply loss",3,0); grantAdmitted(c.getId(),1L);
        String id=UUID.randomUUID().toString(); bookings.book(c.getId(),1L,id);
        loseReply=true;
        var response=payments.tossConfirm(c.getId(),1L,request(UUID.randomUUID().toString()));
        loseReply=false;
        // Historical already-cancelled Booking: no resurrection; refund before returning stock.
        jdbcTemplate.update("UPDATE booking SET status='CANCELLED' WHERE concert_id=?",c.getId());
        loseCancel=true; saga.recover(response.getPaymentId());
        assertThat(cancelCalls.get()).isEqualTo(1);
        assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(2);
        assertThat(outboxRepository.count()).isZero();
        loseCancel=false; saga.recover(response.getPaymentId());
        assertThat(paymentRepository.findById(response.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(outboxRepository.count()).isEqualTo(1);
        var event=outboxRepository.findAll().get(0).getId(); compensation.process(event); compensation.process(event);
        assertReturned(c.getId(),id);
        saga.recover(response.getPaymentId());
        assertThat(cancelCalls.get()).isEqualTo(1);
    }
    @Test void queryFailureAndOldOutboxCannotReturnPendingPayment() throws Exception {
        var c=savedConcert("pending",3,0); grantAdmitted(c.getId(),1L);
        String id=UUID.randomUUID().toString(); bookings.book(c.getId(),1L,id);
        loseReply=true; var response=payments.tossConfirm(c.getId(),1L,request(UUID.randomUUID().toString()));
        queryFails=true; saga.recover(response.getPaymentId());
        var event=outboxRepository.saveAndFlush(PaymentCompensationOutbox.create(json.writeValueAsString(java.util.Map.of("bookingId",response.getBookingId(),"concertId",c.getId(),"paymentId",response.getPaymentId()))));
        assertThatThrownBy(() -> compensation.process(event.getId())).isInstanceOf(studying.blog.exception.PaymentDeferredException.class);
        assertThat(outboxRepository.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(2);
        assertThat(attemptRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
    }
    @Test void finalProviderFailureReturnsOnce() throws Exception {
        var c=savedConcert("failed",3,0); grantAdmitted(c.getId(),1L);
        String id=UUID.randomUUID().toString(); bookings.book(c.getId(),1L,id); pgStatus="ABORTED";
        var response=payments.tossConfirm(c.getId(),1L,request(UUID.randomUUID().toString()));
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.FAILED);
        var event=outboxRepository.findAll().get(0).getId(); compensation.process(event); compensation.process(event);
        assertReturned(c.getId(),id);
    }
    @Test void concurrentSameOrderAndRecoveryExpiryRace() throws Exception {
        var c=savedConcert("concurrent",3,0); grantAdmitted(c.getId(),1L);
        String id=UUID.randomUUID().toString(); bookings.book(c.getId(),1L,id);
        String order=UUID.randomUUID().toString(); var request=request(order); loseReply=true;
        var pool=Executors.newFixedThreadPool(8);
        try {
            var tasks=new java.util.ArrayList<Future<studying.blog.dto.PaymentResponse>>();
            for(int i=0;i<8;i++) tasks.add(pool.submit(() -> payments.tossConfirm(c.getId(),1L,request)));
            Long paymentId=null;
            for(var task:tasks) { var response=task.get(15,TimeUnit.SECONDS); paymentId=response.getPaymentId(); }
            assertThat(calls.get()).isEqualTo(1); assertThat(paymentRepository.count()).isEqualTo(1);
            var mockRequest=json.readValue("{\"idempotencyKey\":\"another-order\",\"paymentMethod\":\"MOCK\"}",studying.blog.dto.PaymentRequest.class);
            assertThatThrownBy(() -> payments.pay(c.getId(),1L,mockRequest)).isInstanceOf(IllegalStateException.class);
            jdbcTemplate.update("UPDATE booking SET created_at=? WHERE concert_id=?",LocalDateTime.now().minusMinutes(31),c.getId());
            loseReply=false; final Long selected=paymentId;
            var recover=pool.submit(() -> saga.recover(selected));
            var expire=pool.submit(() -> expiry.expireStaleBookings());
            recover.get(15,TimeUnit.SECONDS); expire.get(15,TimeUnit.SECONDS);
            assertConfirmed(c.getId(),id);
        } finally { pool.shutdownNow(); }
    }
    @Test void duePaymentIsRecoveredThroughWorkerScan() throws Exception {
        var c=savedConcert("worker scan",3,0); grantAdmitted(c.getId(),1L);
        String id=UUID.randomUUID().toString(); bookings.book(c.getId(),1L,id);
        loseReply=true; var response=payments.tossConfirm(c.getId(),1L,request(UUID.randomUUID().toString()));
        loseReply=false;
        jdbcTemplate.update("UPDATE payment SET next_retry_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),response.getPaymentId());
        worker.recover(); assertConfirmed(c.getId(),id);
    }
    @Test void cancellationCommitFailureIsRecoveredFromPgState() throws Exception {
        var c=savedConcert("cancel DB failure",3,0); grantAdmitted(c.getId(),1L);
        String id=UUID.randomUUID().toString(); bookings.book(c.getId(),1L,id);
        loseReply=true; var response=payments.tossConfirm(c.getId(),1L,request(UUID.randomUUID().toString())); loseReply=false;
        jdbcTemplate.update("UPDATE booking SET status='CANCELLED' WHERE concert_id=?",c.getId());
        jdbcTemplate.execute("CREATE TRIGGER fault_pg_payment_update BEFORE UPDATE ON payment FOR EACH ROW BEGIN IF NEW.status = 'CANCELED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cancel commit rejected'; END IF; END");
        saga.recover(response.getPaymentId());
        assertThat(canceled).isTrue(); assertThat(outboxRepository.count()).isZero();
        assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(2);
        jdbcTemplate.execute("DROP TRIGGER fault_pg_payment_update"); saga.recover(response.getPaymentId());
        assertThat(outboxRepository.count()).isEqualTo(1);
        compensation.process(outboxRepository.findAll().get(0).getId()); assertReturned(c.getId(),id);
        assertThat(cancelCalls.get()).isEqualTo(1);
    }
    @Test void expiredBookingCannotStartApproval() throws Exception {
        var c=savedConcert("expired",3,0); grantAdmitted(c.getId(),1L);
        String id=UUID.randomUUID().toString(); bookings.book(c.getId(),1L,id);
        jdbcTemplate.update("UPDATE booking SET created_at=? WHERE concert_id=?",LocalDateTime.now().minusMinutes(31),c.getId());
        var request=request(UUID.randomUUID().toString());
        assertThatThrownBy(() -> payments.tossConfirm(c.getId(),1L,request)).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isZero(); assertThat(paymentRepository.count()).isZero();
        expiry.expireStaleBookings(); assertReturned(c.getId(),id);
    }
    private void assertConfirmed(Long cid,String id) {
        assertThat(bookingRepository.findByConcertIdAndUserId(cid,1L).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(attemptRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
        assertThat(queueService.getRemainingSeat(cid)).isEqualTo(2);
        assertThat(paymentRepository.findAll().get(0).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(outboxRepository.count()).isZero();
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
