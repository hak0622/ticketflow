package studying.blog.fault;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import studying.blog.domain.*;
import studying.blog.scheduler.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class PaymentCircuitBreakerGuardIntegrationTest extends CircuitPgSupport {
 @Autowired BookingExpiryScheduler expiry;
 @Autowired PaymentCompensationOutboxProcessor processor;
 @Test void expiredBookingAndHistoricalOutboxCannotReturnBlockedPendingPayment()throws Exception{
  var c=savedConcert("pending guard",3,0);grantAdmitted(c.getId(),1L);String id=UUID.randomUUID().toString();bookings.book(c.getId(),1L,id);
  loseApproval=true;var r=payments.tossConfirm(c.getId(),1L,request("guard-key"));loseApproval=false;
  var b=bookingRepository.findByConcertIdAndUserId(c.getId(),1L).orElseThrow();
  var old=Payment.create(b,1L,c.getId(),90000,"CARD",UUID.randomUUID().toString());old.fail("historical fixture");old=paymentRepository.saveAndFlush(old);
  var event=outboxRepository.saveAndFlush(PaymentCompensationOutbox.create(json.writeValueAsString(Map.of("bookingId",b.getId(),"concertId",c.getId(),"userId",1L,"paymentId",old.getId()))));
  jdbcTemplate.update("UPDATE booking SET created_at=? WHERE id=?",LocalDateTime.now().minusMinutes(31),b.getId());
  open();saga.recover(r.getPaymentId());expiry.expireStaleBookings();
  assertThatThrownBy(()->processor.process(event.getId())).isInstanceOf(studying.blog.exception.PaymentDeferredException.class);
  assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(2);assertThat(bookingRepository.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
  assertThat(outboxRepository.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
  assertThat(outboxRepository.findById(event.getId()).orElseThrow().getRetryCount()).isZero();
  assertThat(attemptRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
  assertThat(redisTemplate.opsForHash().get("booking:attempt:"+id,"state")).isEqualTo("HELD");
 }
}
