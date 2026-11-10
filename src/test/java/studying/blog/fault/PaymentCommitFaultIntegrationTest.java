package studying.blog.fault;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.config.BeanPostProcessor;
import studying.blog.service.PaymentRetryRound;
import studying.blog.domain.PaymentStatus;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Import(PaymentCommitFaultIntegrationTest.Config.class)
class PaymentCommitFaultIntegrationTest extends PaymentRetryRoundIntegrationTest {
    static Path DIR;
    static {
        try {DIR=Files.createTempDirectory("payment-commit-jdbc-");System.setProperty("fault.commit.root",DIR.toString());}
        catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    @org.junit.jupiter.api.BeforeEach void isolateCommitGateDirectory() throws Exception {
        DIR=Files.createTempDirectory("payment-commit-case-");
        System.setProperty("fault.commit.root",DIR.toString());
    }
    @TestConfiguration static class Config {
        @Bean static BeanPostProcessor commitFaultBpp(){return PaymentCommitFaultProbe.commitProbe();}
    }
    @org.springframework.beans.factory.annotation.Autowired studying.blog.service.BookingSeatReleaseService release;
    @org.springframework.beans.factory.annotation.Autowired studying.blog.scheduler.PaymentCompensationOutboxProcessor compensation;
    @Test void realCommitVisibleWhileJdbcAckIsWithheldThenSpringSeesFailure() throws Exception { runLoss(false); }
    @Test void staleWorkerExpiryAndHistoricalOutboxCannotReturnCommittedTicket() throws Exception { runLoss(true); }
    @Test void pendingCommitAndOtherPaymentCompletionDoNotTriggerFault() throws Exception {
        String key="negative-"+payment.getId();
        Path arm=DIR.resolve("commit-target-test.arm");
        try {
            Files.writeString(arm,payment.getId()+","+payment.getBooking().getId()+","+key);
            var pending=paymentRepository.findById(payment.getId()).orElseThrow();pending.retry("pending control");
            paymentRepository.saveAndFlush(pending);
            assertThat(Files.exists(DIR.resolve(key+"-commit.claimed"))).isFalse();
            Files.writeString(arm,(payment.getId()+100000)+","+payment.getBooking().getId()+","+key);
            saga.recover(payment.getId());
            assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(Files.exists(DIR.resolve(key+"-commit.claimed"))).isFalse();
        } finally {Files.deleteIfExists(arm);}
    }
    private void runLoss(boolean race) throws Exception {
        var selected=PaymentRetryRound.from(paymentRepository.findById(payment.getId()).orElseThrow());
        Long historicalEvent=null;
        if(race) {
            jdbcTemplate.update("UPDATE booking SET created_at=DATE_SUB(created_at,INTERVAL 31 MINUTE) WHERE id=?",payment.getBooking().getId());
            var failed=studying.blog.domain.Payment.create(payment.getBooking(),1L,payment.getConcertId(),90000,"MOCK",java.util.UUID.randomUUID().toString());
            failed.fail("historical fixture");failed=paymentRepository.saveAndFlush(failed);
            var event=studying.blog.domain.PaymentCompensationOutbox.create("{\"bookingId\":"+payment.getBooking().getId()+",\"concertId\":"+payment.getConcertId()+",\"paymentId\":"+failed.getId()+"}");
            historicalEvent=outboxRepository.saveAndFlush(event).getId();
        }
        String key="case-"+payment.getId();
        Files.writeString(DIR.resolve("commit-target-test.arm"),payment.getId()+","+payment.getBooking().getId()+","+key);
        var pool=Executors.newFixedThreadPool(4);
        try {
            var pending=pool.submit(()->saga.recover(payment.getId(),PaymentRetryRound.from(paymentRepository.findById(payment.getId()).orElseThrow())));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            Path log=DIR.resolve("commit-test.tsv");
            while(!Files.exists(log)||!Files.readString(log).contains(key+"\treal_commit_returned")) {
                assertThat(System.nanoTime()).isLessThan(end);Thread.sleep(10);
            }
            assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            if(race) {
                final Long event=historicalEvent;
                var stale=pool.submit(()->saga.recover(payment.getId(),selected));
                var expire=pool.submit(()->release.cancelAndRelease(payment.getBooking().getId(),java.time.LocalDateTime.now().minusMinutes(30)));
                var compensate=pool.submit(()->{compensation.process(event);return true;});
                stale.get(5,TimeUnit.SECONDS);expire.get(5,TimeUnit.SECONDS);compensate.get(5,TimeUnit.SECONDS);
                assertThat(outboxRepository.findById(event).orElseThrow().getStatus()).isEqualTo(studying.blog.domain.OutboxStatus.PUBLISHED);
            }
            Files.writeString(DIR.resolve(key+"-commit.resume"),"");pending.get(5,TimeUnit.SECONDS);
            assertThat(Files.readString(log)).contains(key+"\tjdbc_exception").contains(key+"\tspring_commit_failure");
            assertThat(Files.readString(log)).doesNotContain(key+"\tspring_commit_failure\tNONE");
            assertThat(outboxRepository.count()).isEqualTo(race?1:0);
            assertThat(queueService.getRemainingSeat(payment.getConcertId())).isEqualTo(2);
        } finally {Files.writeString(DIR.resolve(key+"-commit.resume"),"");Files.deleteIfExists(DIR.resolve("commit-target-test.arm"));pool.shutdownNow();}
    }
}
