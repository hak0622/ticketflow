package studying.blog.fault;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import studying.blog.domain.*;
import studying.blog.dto.PaymentRequest;
import studying.blog.service.PaymentDeadlinePolicy;
import static org.assertj.core.api.Assertions.*;

/** Inherits the real HTTP PG fixture and its existing Saga regression tests. */
@ContextConfiguration(classes=PaymentDeadlineIntegrationTest.TimeConfig.class)
class PaymentDeadlineIntegrationTest extends PaymentSagaIntegrationTest {
    static class TestClock extends Clock {
        volatile Instant instant = Instant.now();
        public ZoneId getZone() { return ZoneId.systemDefault(); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        public Instant instant() { return instant; }
        void at(LocalDateTime time) { instant=time.atZone(getZone()).toInstant(); }
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary TestClock boundaryClock() { return new TestClock(); }
    }
    @Autowired TestClock clock;
    @Autowired PaymentDeadlinePolicy deadline;
    @BeforeEach void resetTime() { clock.instant=Instant.now(); }

    private studying.blog.dto.TossConfirmRequest request(String order) {
        var req=new studying.blog.dto.TossConfirmRequest();
        ReflectionTestUtils.setField(req,"paymentKey","local-only");
        ReflectionTestUtils.setField(req,"orderId",order);
        ReflectionTestUtils.setField(req,"amount",90000);
        return req;
    }
    Booking fixture() {
        var concert=savedConcert("deadline",3,0);
        grantAdmitted(concert.getId(),1L);
        bookings.book(concert.getId(),1L,UUID.randomUUID().toString());
        return bookingRepository.findByConcertIdAndUserId(concert.getId(),1L).orElseThrow();
    }
    void at(Booking b, long offsetSeconds) { clock.at(b.getCreatedAt().plusMinutes(10).plusSeconds(offsetSeconds)); }
    PaymentRequest mockRequest(String key) {
        var req=new PaymentRequest();
        ReflectionTestUtils.setField(req,"idempotencyKey",key);
        ReflectionTestUtils.setField(req,"paymentMethod","MOCK");
        return req;
    }
    @ParameterizedTest @CsvSource({"false,-1","false,0","false,1","true,-1","true,0","true,1"})
    void admissionBoundary(boolean mock, long offset) {
        var b=fixture(); at(b,offset); var cid=b.getConcert().getId();
        var attempt=attemptRepository.findByBookingId(b.getId()).orElseThrow();
        var before=redisTemplate.opsForHash().entries("booking:attempt:"+attempt.getId());
        ReflectionTestUtils.setField(payments,"mockEnabled",true);
        try {
            if(offset<0) {
                if(mock) payments.pay(cid,1L,mockRequest(UUID.randomUUID().toString()));
                else payments.tossConfirm(cid,1L,request(UUID.randomUUID().toString()));
                assertThat(bookingRepository.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
                expiry.expireStaleBookings();
                assertThat(queueService.getRemainingSeat(cid)).isEqualTo(2);
            } else {
                assertThatThrownBy(() -> {
                    if(mock) payments.pay(cid,1L,mockRequest(UUID.randomUUID().toString()));
                    else payments.tossConfirm(cid,1L,request(UUID.randomUUID().toString()));
                }).isInstanceOf(IllegalStateException.class);
                assertThat(calls.get()).isZero();
                assertThat(paymentRepository.count()).isZero();
                assertThat(outboxRepository.count()).isZero();
                assertThat(bookingRepository.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
                assertThat(redisTemplate.opsForHash().entries("booking:attempt:"+attempt.getId())).isEqualTo(before);
                assertThat(queueService.getRemainingSeat(cid)).isEqualTo(2);
                expiry.expireStaleBookings(); expiry.expireStaleBookings();
                assertThat(bookingRepository.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
                assertThat(queueService.getRemainingSeat(cid)).isEqualTo(3);
                assertThat(redisTemplate.opsForHash().get("booking:attempt:"+attempt.getId(),"releaseOutcome")).isEqualTo("RETURNED");
            }
        } finally { ReflectionTestUtils.setField(payments,"mockEnabled",false); }
    }
    @Test void millisecondsPolicyBoundary() {
        var created=LocalDateTime.of(2026,1,1,12,0);
        clock.at(created.plusMinutes(10).minusNanos(1_000_000)); assertThat(deadline.expired(created)).isFalse();
        clock.at(created.plusMinutes(10)); assertThat(deadline.expired(created)).isTrue();
        clock.at(created.plusMinutes(10).plusNanos(1_000_000)); assertThat(deadline.expired(created)).isTrue();
    }
    @Test void committedPendingBeforeDispatchSurvivesDeadline() throws Exception {
        var b=fixture(); at(b,-1); var req=request(UUID.randomUUID().toString());
        var prepared=new java.util.concurrent.CountDownLatch(1);
        var dispatch=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var task=pool.submit(() -> {
                // Actual preparation transaction commits before the test-only boundary gate.
                ReflectionTestUtils.invokeMethod(saga,"preparePayment",b.getConcert().getId(),1L,req);
                prepared.countDown();
                if(!dispatch.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("dispatch timeout");
                return payments.tossConfirm(b.getConcert().getId(),1L,req);
            });
            assertThat(prepared.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(jdbcTemplate.queryForObject("select count(*) from payment where status='PENDING' and retry_count=0",Integer.class)).isEqualTo(1);
            at(b,1); expiry.expireStaleBookings();
            assertThat(queueService.getRemainingSeat(b.getConcert().getId())).isEqualTo(2);
            assertThat(calls.get()).isZero();
            dispatch.countDown();
            assertThat(task.get(5,java.util.concurrent.TimeUnit.SECONDS).getStatus().toString()).isEqualTo("COMPLETED");
            payments.tossConfirm(b.getConcert().getId(),1L,req);
            assertThat(calls.get()).isEqualTo(1); assertThat(outboxRepository.count()).isZero();
        } finally { dispatch.countDown(); pool.shutdownNow(); }
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void lockWaitCrossingDeadlineRejects(boolean mock) throws Exception {
        var mysql=(org.testcontainers.containers.MySQLContainer<?>) ReflectionTestUtils.getField(studying.blog.support.IntegrationTestSupport.class,"MYSQL");
        var grant=mysql.execInContainer("mysql","-uroot","-ptest","-e","GRANT SELECT ON performance_schema.* TO 'test'@'%'");
        assertThat(grant.getExitCode()).isZero();
        var b=fixture(); at(b,-1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked=new java.util.concurrent.CountDownLatch(1);
        var unlock=new java.util.concurrent.CountDownLatch(1);
        ReflectionTestUtils.setField(payments,"mockEnabled",true);
        try {
            var holder=pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
                bookingRepository.findLockedById(b.getId()).orElseThrow(); locked.countDown();
                try { if(!unlock.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("unlock timeout"); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var caller=pool.submit(() -> {
                if(mock) return payments.pay(b.getConcert().getId(),1L,mockRequest(UUID.randomUUID().toString()));
                return payments.tossConfirm(b.getConcert().getId(),1L,request(UUID.randomUUID().toString()));
            });
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            int waits=0;
            while(System.nanoTime()<end) {
                waits=jdbcTemplate.queryForObject("select count(*) from performance_schema.data_lock_waits",Integer.class);
                if(waits>0) break;
                Thread.sleep(10);
            }
            assertThat(waits).as("actual MySQL row lock wait").isPositive();
            at(b,1); unlock.countDown(); holder.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> caller.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(paymentRepository.count()).isZero(); assertThat(outboxRepository.count()).isZero();
            assertThat(calls.get()).isZero(); assertThat(queueService.getRemainingSeat(b.getConcert().getId())).isEqualTo(2);
        } finally { unlock.countDown(); pool.shutdown(); assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); ReflectionTestUtils.setField(payments,"mockEnabled",false); }
    }
    @Test void lostApprovalCanRecoverAfterDeadline() {
        var b=fixture(); at(b,-1); var req=request(UUID.randomUUID().toString());
        loseReply=true;
        var pending=payments.tossConfirm(b.getConcert().getId(),1L,req);
        assertThat(pending.getStatus().toString()).isEqualTo("PENDING");
        at(b,1); expiry.expireStaleBookings();
        assertThat(queueService.getRemainingSeat(b.getConcert().getId())).isEqualTo(2);
        loseReply=false; saga.recover(pending.getPaymentId());
        assertThat(bookingRepository.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(calls.get()).isEqualTo(1); assertThat(outboxRepository.count()).isZero();
    }
    @Test void mockReplayAfterDeadlineReusesResult() {
        var b=fixture(); at(b,-1); var req=mockRequest(UUID.randomUUID().toString());
        ReflectionTestUtils.setField(payments,"mockEnabled",true);
        try {
            var first=payments.pay(b.getConcert().getId(),1L,req); at(b,1);
            var replay=payments.pay(b.getConcert().getId(),1L,req);
            assertThat(replay.getPaymentId()).isEqualTo(first.getPaymentId());
            assertThat(paymentRepository.count()).isEqualTo(1); assertThat(queueService.getRemainingSeat(b.getConcert().getId())).isEqualTo(2);
        } finally { ReflectionTestUtils.setField(payments,"mockEnabled",false); }
    }

    @AfterEach void preserveSnapshot(org.junit.jupiter.api.TestInfo info) throws Exception {
        var snapshot=new java.util.LinkedHashMap<String,Object>();
        for(String table:java.util.List.of("booking","booking_attempt","payment","payment_compensation_outbox")) {
            snapshot.put(table,jdbcTemplate.queryForList("select * from "+table));
        }
        var inventory=new java.util.LinkedHashMap<String,Object>();
        for(var attempt:attemptRepository.findAll()) {
            inventory.put(attempt.getId(),redisTemplate.opsForHash().entries("booking:attempt:"+attempt.getId()));
            inventory.put("seats:"+attempt.getConcertId(),queueService.getRemainingSeat(attempt.getConcertId()));
        }
        snapshot.put("redis",inventory); snapshot.put("approvalCalls",calls.get()); snapshot.put("cancelCalls",cancelCalls.get());
        var dir=java.nio.file.Path.of("test-results/payment-expiry-boundary/current/snapshots");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve(info.getTestMethod().orElseThrow().getName()+"-"+UUID.randomUUID()+".json"),json.writeValueAsString(snapshot));
    }

}
