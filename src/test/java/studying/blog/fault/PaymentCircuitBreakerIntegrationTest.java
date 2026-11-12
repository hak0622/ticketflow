package studying.blog.fault;
import org.junit.jupiter.api.*;
import io.github.resilience4j.circuitbreaker.*;
import java.util.concurrent.*;
import java.util.*;
import studying.blog.domain.*;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class PaymentCircuitBreakerIntegrationTest extends CircuitPgSupport {
 @Test void readTimeoutIsSocketTimeout() {
  delay=3600;
  long started=System.nanoTime();
  assertThatThrownBy(this::lookup)
    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
    .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
  long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
  assertThat(elapsed).isGreaterThanOrEqualTo(2900);
  assertThat(httpOutcomes).anySatisfy(outcome->{
   assertThat(outcome.get("outcome")).isEqualTo(java.net.SocketTimeoutException.class.getName());
   assertThat((Long)outcome.get("elapsedMs")).isGreaterThanOrEqualTo(2900);
  });
  events.add("read_timeout_call_ms="+elapsed);
  assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
 }
 @Test void normal(){lookup();assertThat(arrivals).hasSize(1);assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);}
 @Test void minimumAndBlocking(){failing=true;for(int i=0;i<9;i++)error();assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);assertThat(cb.getMetrics().getFailureRate()).isEqualTo(-1);error();assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);int n=arrivals.size();assertThatThrownBy(this::lookup).isInstanceOf(CallNotPermittedException.class);assertThatThrownBy(()->pg.approve("x","probe-order",90000,"key")).isInstanceOf(CallNotPermittedException.class);assertThatThrownBy(()->pg.cancel("x","reason",90000,"key")).isInstanceOf(CallNotPermittedException.class);assertThat(arrivals).hasSize(n);assertThat(cb.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(3);}
 @Test void rateBoundary(){for(int i=0;i<6;i++)lookup();failing=true;for(int i=0;i<4;i++)error();assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);error();assertThat(cb.getMetrics().getFailureRate()).isEqualTo(50);assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);}
 @Test void halfOpenSuccess()throws Exception{open();waitOpen();lookup();assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);lookup();lookup();assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);lookup();}
 @Test void halfOpenFailure()throws Exception{open();waitOpen();failing=true;error();error();failing=false;lookup();assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);}
 @Test void halfOpenMinorityFailure()throws Exception{open();waitOpen();failing=true;error();failing=false;lookup();lookup();assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);}
 @Test void halfOpenConcurrentLimit()throws Exception{open();waitOpen();gate=new CountDownLatch(1);int n=arrivals.size();var futures=new ArrayList<Future<?>>();for(int i=0;i<3;i++)futures.add(executor.submit(this::lookup));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(arrivals.size()<n+3&&System.nanoTime()<until)Thread.sleep(5);assertThat(arrivals).hasSize(n+3);assertThatThrownBy(this::lookup).isInstanceOf(CallNotPermittedException.class);assertThat(arrivals).hasSize(n+3);gate.countDown();for(var f:futures)f.get(3,TimeUnit.SECONDS);assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);}
 @Test void slowRate(){for(int i=0;i<3;i++)lookup();delay=2200;for(int i=0;i<7;i++)lookup();assertThat(cb.getMetrics().getSlowCallRate()).isEqualTo(70);assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);lookup();assertThat(cb.getMetrics().getFailureRate()).isZero();assertThat(cb.getMetrics().getSlowCallRate()).isEqualTo(80);assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);}
 @Test void approvalTimeoutAndReconciliation()throws Exception{var c=savedConcert("timeout",3,0);grantAdmitted(c.getId(),1L);String id=UUID.randomUUID().toString();bookings.book(c.getId(),1L,id);var req=request("approved-key");loseApproval=true;var response=payments.tossConfirm(c.getId(),1L,req);assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);assertThat(approvals).isEqualTo(1);assertThat(outboxRepository.count()).isZero();assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(2);loseApproval=false;saga.recover(response.getPaymentId());assertThat(paymentRepository.findById(response.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);assertThat(bookingRepository.findByConcertIdAndUserId(c.getId(),1L).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);assertThat(attemptRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);assertThat(approvals).isEqualTo(1);assertThat(outboxRepository.count()).isZero();}
 @Test void blockedInitialApprovalRemainsPending()throws Exception{var c=savedConcert("blocked",3,0);grantAdmitted(c.getId(),1L);String id=UUID.randomUUID().toString();bookings.book(c.getId(),1L,id);open();var response=payments.tossConfirm(c.getId(),1L,request("never-approved"));assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);assertThat(approvals).isZero();int n=arrivals.size();saga.recover(response.getPaymentId());assertThat(arrivals).hasSize(n);assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(2);assertThat(outboxRepository.count()).isZero();waitOpen();saga.recover(response.getPaymentId());assertThat(arrivals).hasSize(n+1);assertThat(approvals).isZero();assertThat(paymentRepository.findById(response.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);assertThat(outboxRepository.count()).isZero();}
}
