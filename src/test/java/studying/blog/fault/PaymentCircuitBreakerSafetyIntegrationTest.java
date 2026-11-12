package studying.blog.fault;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import studying.blog.domain.*;
import studying.blog.scheduler.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class PaymentCircuitBreakerSafetyIntegrationTest extends CircuitPgSupport {
 @Autowired BookingExpiryScheduler expiry;
 @Autowired PaymentCompensationOutboxProcessor outbox;
 void pending(Long cid,String id,Long paymentId)throws Exception{
  assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
  assertThat(queueService.getRemainingSeat(cid)).isEqualTo(2);
  assertThat(redisTemplate.opsForHash().get("booking:attempt:"+id,"state")).isEqualTo("HELD");
  expiry.expireStaleBookings();assertThat(outboxRepository.count()).isZero();
 }
 @Test void approvedPaymentSurvivesBlockedLookup()throws Exception{
  var c=savedConcert("approved blocked",3,0);grantAdmitted(c.getId(),1L);String id=UUID.randomUUID().toString();bookings.book(c.getId(),1L,id);
  var req=request("approved-held");loseApproval=true;var r=payments.tossConfirm(c.getId(),1L,req);loseApproval=false;
  open();int n=arrivals.size();saga.recover(r.getPaymentId());assertThat(arrivals).hasSize(n);pending(c.getId(),id,r.getPaymentId());
  waitOpen();saga.recover(r.getPaymentId());assertThat(paymentRepository.findById(r.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
  assertThat(payments.tossConfirm(c.getId(),1L,req).getStatus()).isEqualTo(PaymentStatus.COMPLETED);assertThat(approvals).isEqualTo(1);assertThat(outboxRepository.count()).isZero();
 }
 @Test void cancelIntentWaitsForCircuitAndReturnsOnce()throws Exception{cancellation(false);}
 @Test void canceledReplyLossWaitsForCircuitAndReturnsOnce()throws Exception{cancellation(true);}
 void cancellation(boolean loseCancelResponse)throws Exception{
  var c=savedConcert("cancel blocked",3,0);grantAdmitted(c.getId(),1L);String id=UUID.randomUUID().toString();bookings.book(c.getId(),1L,id);
  var req=request("cancel-held");loseApproval=true;var r=payments.tossConfirm(c.getId(),1L,req);loseApproval=false;
  jdbcTemplate.update("UPDATE payment SET recovery_phase='CANCEL_REQUIRED',cancel_reason='test fixture cancellation' WHERE id=?",r.getPaymentId());
  if(loseCancelResponse){loseCancel=true;saga.recover(r.getPaymentId());loseCancel=false;assertThat(cancellations).isEqualTo(1);}
  open();int n=arrivals.size();saga.recover(r.getPaymentId());assertThat(arrivals).hasSize(n);pending(c.getId(),id,r.getPaymentId());
  waitOpen();saga.recover(r.getPaymentId());assertThat(paymentRepository.findById(r.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
  assertThat(outboxRepository.count()).isEqualTo(1);Long event=outboxRepository.findAll().get(0).getId();outbox.process(event);outbox.process(event);saga.recover(r.getPaymentId());
  assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(3);assertThat(cancellations).isEqualTo(1);assertThat(approvals).isEqualTo(1);
  assertThat(redisTemplate.opsForHash().get("booking:attempt:"+id,"releaseOutcome")).isEqualTo("RETURNED");assertThat(attemptRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
  assertThat(bookingRepository.findByConcertIdAndUserId(c.getId(),1L).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
 }
}
