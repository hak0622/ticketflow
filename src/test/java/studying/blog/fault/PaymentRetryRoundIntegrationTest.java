package studying.blog.fault;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import studying.blog.domain.*;
import studying.blog.service.*;
import studying.blog.support.IntegrationTestSupport;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL/Redis, real HTTP; scheduler interleavings additionally run in perf/payment_retry_fault.py. */
@Tag("integration")
class PaymentRetryRoundIntegrationTest extends IntegrationTestSupport {
    @Autowired PaymentSaga saga;
    @Autowired BookingService bookings;
    @Autowired TossPaymentClient pg;
    @Autowired io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry breakers;
    HttpServer server;
    ExecutorService httpThreads;
    Object pgTarget;
    RestClient original;
    Payment payment;
    String attemptId;
    volatile boolean queryFails;
    volatile boolean cancelFails;
    volatile String status = "DONE";
    volatile String method = "CARD";
    volatile int balance = 90000;
    volatile CountDownLatch failedResponseReached;
    volatile CountDownLatch releaseFailedResponse;
    final AtomicInteger lookupCalls = new AtomicInteger();
    final AtomicInteger cancelCalls = new AtomicInteger();

    @BeforeEach void setupPgAndPayment() throws Exception {
        breakers.circuitBreaker("toss").reset();
        var concert = savedConcert("retry round", 3, 0);
        grantAdmitted(concert.getId(), 1L);
        attemptId = UUID.randomUUID().toString();
        bookings.book(concert.getId(), 1L, attemptId);
        var booking = bookingRepository.findByConcertIdAndUserId(concert.getId(), 1L).orElseThrow();
        payment = Payment.create(booking, 1L, concert.getId(), 90000, "TOSS", UUID.randomUUID().toString());
        payment.preparePg("retry-local-only");
        payment.retry("dispatch claimed");
        payment = paymentRepository.saveAndFlush(payment);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpThreads = Executors.newCachedThreadPool();
        server.setExecutor(httpThreads);
        server.createContext("/", exchange -> {
            boolean cancellation = exchange.getRequestURI().getPath().endsWith("/cancel");
            if (cancellation) cancelCalls.incrementAndGet(); else lookupCalls.incrementAndGet();
            boolean fails = cancellation ? cancelFails : queryFails;
            String capturedStatus = cancellation ? "CANCELED" : status;
            String capturedMethod = method;
            int capturedBalance = cancellation ? 0 : balance;
            if (fails && failedResponseReached != null) {
                failedResponseReached.countDown();
                try { if (!releaseFailedResponse.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            byte[] body = (fails ? "{}" : "{\"method\":\""+capturedMethod+"\",\"paymentKey\":\"retry-local-only\",\"orderId\":\""
                    +payment.getIdempotencyKey()+"\",\"totalAmount\":90000,\"balanceAmount\":"+capturedBalance
                    +",\"status\":\""+capturedStatus+"\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(fails ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        pgTarget = AopTestUtils.getUltimateTargetObject(pg);
        original = (RestClient) ReflectionTestUtils.getField(pgTarget, "restClient");
        ReflectionTestUtils.setField(pgTarget, "restClient", original.mutate()
                .requestInterceptor((request, body, execution) -> execution.execute(new HttpRequestWrapper(request) {
                    @Override public URI getURI() {
                        return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+request.getURI().getPath());
                    }
                }, body)).build());
    }

    @AfterEach void cleanup() {
        if (releaseFailedResponse != null) releaseFailedResponse.countDown();
        if (original != null) ReflectionTestUtils.setField(pgTarget, "restClient", original);
        if (server != null) server.stop(0);
        if (httpThreads != null) httpThreads.shutdownNow();
    }

    private Payment current() { return paymentRepository.findById(payment.getId()).orElseThrow(); }
    private PaymentRetryRound selected() { return PaymentRetryRound.from(current()); }
    private void run(PaymentRetryRound round) { saga.recover(payment.getId(), round); }
    private void assertSameRetry(Payment before) {
        Payment after = current();
        assertThat(after.getRetryCount()).isEqualTo(before.getRetryCount());
        assertThat(after.getNextRetryAt()).isEqualTo(before.getNextRetryAt());
        assertThat(after.getLastError()).isEqualTo(before.getLastError());
    }
    private void assertHeld() {
        assertThat(queueService.getRemainingSeat(payment.getConcertId())).isEqualTo(2);
        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
        assertThat(outboxRepository.count()).isZero();
    }

    @Test void concurrentFailuresOfOneSelectedRoundIncrementOnce() throws Exception {
        queryFails = true;
        var round = selected();
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> run(round));
            var second = threads.submit(() -> run(round));
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertThat(lookupCalls.get()).isEqualTo(2);
            assertThat(current().getRetryCount()).isEqualTo(round.retryCount()+1);
            Payment before = current();
            run(round);
            assertSameRetry(before);
            assertHeld();
        } finally { threads.shutdownNow(); }
    }

    @Test void previouslySelectedSuccessStillConfirmsAfterAnotherFailure() {
        var round = selected(); queryFails = true; run(round);
        queryFails = false; run(round);
        assertThat(current().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(current().getNextRetryAt()).isNull();
        assertThat(bookingRepository.findById(payment.getBooking().getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertHeld();
    }

    @Test void veryOldFailureCannotModifyNewRetryRound() {
        queryFails = true;
        var old = selected(); run(old);
        var next = selected(); run(next);
        Payment before = current(); run(old);
        assertThat(before.getRetryCount()).isEqualTo(old.retryCount()+2);
        assertSameRetry(before); assertHeld();
    }

    @Test void nonFinalResponseAndHttpFailureShareOneRound() {
        var round = selected(); status = "IN_PROGRESS"; run(round);
        Payment before = current(); queryFails = true; run(round);
        assertThat(before.getRetryCount()).isEqualTo(round.retryCount()+1);
        assertSameRetry(before); assertHeld();
    }

    @Test void httpFailureThenNonFinalAndUnsupportedResponsesCannotIncrementAgain() {
        var round = selected(); queryFails = true; run(round);
        Payment before = current(); queryFails = false; status = "IN_PROGRESS"; run(round);
        method = "UNSUPPORTED"; run(round);
        method = "CARD"; status = "DONE"; balance = 1; run(round);
        assertSameRetry(before); assertHeld();
    }

    @Test void ownCancellationTransitionStillRecordsCancelFailureAndDoesNotResetDeadline() {
        jdbcTemplate.update("UPDATE payment SET recovery_phase='CANCEL_REQUIRED',cancel_reason='local fixture',version=version+1 WHERE id=?",payment.getId());
        var round = selected(); cancelFails = true; run(round);
        Payment before = current();
        assertThat(before.getRecoveryPhase()).isEqualTo(PaymentRecoveryPhase.CANCEL_CHECK);
        assertThat(before.getRetryCount()).isEqualTo(round.retryCount()+1);
        run(round);
        assertSameRetry(before);
        var next = selected(); run(next);
        assertThat(current().getRetryCount()).isEqualTo(next.retryCount()+1);
        assertHeld();
    }

    @Test void staleCancellationSuccessStillCreatesOnlyOneOutbox() {
        jdbcTemplate.update("UPDATE payment SET recovery_phase='CANCEL_REQUIRED',cancel_reason='local fixture',version=version+1 WHERE id=?",payment.getId());
        var round = selected(); queryFails = true; run(round);
        queryFails = false; run(round); run(round);
        assertThat(current().getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(cancelCalls.get()).isEqualTo(1);
        assertThat(queueService.getRemainingSeat(payment.getConcertId())).isEqualTo(2);
    }

    @Test void inFlightFailureAfterCompletionCannotModifyTerminalPayment() throws Exception {
        queryFails = true; failedResponseReached = new CountDownLatch(1); releaseFailedResponse = new CountDownLatch(1);
        var round = selected(); var thread = Executors.newSingleThreadExecutor();
        try {
            var failure = thread.submit(() -> run(round));
            assertThat(failedResponseReached.await(1, TimeUnit.SECONDS)).isTrue();
            queryFails = false; run(round);
            Payment before = current(); releaseFailedResponse.countDown(); failure.get(5, TimeUnit.SECONDS);
            assertThat(current().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertSameRetry(before); assertHeld();
        } finally { releaseFailedResponse.countDown(); thread.shutdownNow(); }
    }
}
