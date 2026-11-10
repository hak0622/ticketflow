package studying.blog.fault;

import org.junit.jupiter.api.Test;
import studying.blog.service.PaymentRetryRound;
import studying.blog.domain.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Inherits the real-DB completion regression; adds cancellation-specific boundaries. */
class PaymentCancelCommitFaultIntegrationTest extends PaymentCommitFaultIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Test void cancellationCommitExceptionThenReturn() throws Exception { cancelLoss(false); }
    @Test void returnAndStaleApprovalExpiryRaceBeforeCancellationCommitException() throws Exception { cancelLoss(true); }
    @Test void cancellationModeDoesNotInjectIntoPendingOrApprovalCompletion() throws Exception {
        String key="cancel-negative-"+payment.getId();Path arm=DIR.resolve("commit-target-test.arm");
        try {
            Files.writeString(arm,payment.getId()+","+payment.getBooking().getId()+","+key+",CANCEL");
            var p=paymentRepository.findById(payment.getId()).orElseThrow();p.retry("pending control");paymentRepository.saveAndFlush(p);
            saga.recover(payment.getId());
            assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            var other=Payment.create(payment.getBooking(),1L,payment.getConcertId(),90000,"MOCK",java.util.UUID.randomUUID().toString());
            other=paymentRepository.saveAndFlush(other);final Long otherId=other.getId();
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{
                var row=paymentRepository.findById(otherId).orElseThrow();row.canceled();paymentRepository.saveAndFlush(row);
                outboxRepository.saveAndFlush(PaymentCompensationOutbox.create("{\"paymentId\":"+otherId+",\"bookingId\":"+payment.getBooking().getId()+",\"concertId\":"+payment.getConcertId()+",\"userId\":1}"));
            });
            assertThat(Files.exists(DIR.resolve(key+"-commit.claimed"))).isFalse();
        } finally { Files.deleteIfExists(arm); }
    }
    private void cancelLoss(boolean returnFirst) throws Exception {
        var oldRound=PaymentRetryRound.from(paymentRepository.findById(payment.getId()).orElseThrow());
        var oldDone=pg.lookup(payment.getPgPaymentKey());
        jdbcTemplate.update("UPDATE payment SET recovery_phase='CANCEL_REQUIRED',cancel_reason='cancel test fixture',version=version+1 WHERE id=?",payment.getId());
        jdbcTemplate.update("UPDATE booking SET created_at=DATE_SUB(created_at,INTERVAL 31 MINUTE) WHERE id=?",payment.getBooking().getId());
        String key="cancel-"+payment.getId();Path arm=DIR.resolve("commit-target-test.arm");
        Files.writeString(arm,payment.getId()+","+payment.getBooking().getId()+","+key+",CANCEL");
        var pool=Executors.newFixedThreadPool(4);
        try {
            var work=pool.submit(()->saga.recover(payment.getId()));
            Path log=DIR.resolve("commit-test.tsv");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!Files.exists(log)||!Files.readString(log).contains(key+"\treal_commit_returned")) {assertThat(System.nanoTime()).isLessThan(end);Thread.sleep(10);}
            var before=paymentRepository.findById(payment.getId()).orElseThrow();assertThat(before.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(outboxRepository.count()).isEqualTo(1);Long event=outboxRepository.findAll().get(0).getId();
            assertThat(queueService.getRemainingSeat(payment.getConcertId())).isEqualTo(2);
            if(returnFirst) {
                var stale=pool.submit(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod((Object)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(saga),"applyResult",payment.getId(),oldDone,oldRound));
                var expire=pool.submit(()->release.cancelAndRelease(payment.getBooking().getId(),java.time.LocalDateTime.now().minusMinutes(30)));
                var compensate=pool.submit(()->{compensation.process(event);return true;});
                stale.get(5,TimeUnit.SECONDS);expire.get(5,TimeUnit.SECONDS);compensate.get(5,TimeUnit.SECONDS);
                assertThat(queueService.getRemainingSeat(payment.getConcertId())).isEqualTo(3);
            }
            Files.writeString(DIR.resolve(key+"-commit.resume"),"");work.get(5,TimeUnit.SECONDS);
            compensation.process(event);compensation.process(event);
            var after=paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(PaymentStatus.CANCELED);assertThat(after.getVersion()).isEqualTo(before.getVersion());
            assertThat(after.getRetryCount()).isEqualTo(before.getRetryCount());assertThat(after.getNextRetryAt()).isNull();
            assertThat(queueService.getRemainingSeat(payment.getConcertId())).isEqualTo(3);
            assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
            assertThat(outboxRepository.findById(event).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(Files.readString(log)).contains(key+"\tspring_commit_failure");
        } finally {Files.writeString(DIR.resolve(key+"-commit.resume"),"");Files.deleteIfExists(arm);pool.shutdownNow();}
    }
}
