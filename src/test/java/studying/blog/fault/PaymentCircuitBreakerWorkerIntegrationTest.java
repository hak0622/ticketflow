package studying.blog.fault;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import studying.blog.domain.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@SpringBootTest(properties={"spring.task.scheduling.enabled=true","circuit.worker.observation=true"})
@Import(PaymentCircuitBreakerWorkerIntegrationTest.ObservationConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentCircuitBreakerWorkerIntegrationTest extends CircuitPgSupport {
 @org.springframework.beans.factory.annotation.Autowired ScanGate scans;
 @AfterEach void releaseGate(){scans.allow.set(true);}
 @Test void automaticWorkerClosesCircuitAndConfirmsThreePayments() throws Exception {
  var c=savedConcert("automatic circuit recovery",5,0);
  var ids=new ArrayList<String>();
  var requests=new ArrayList<studying.blog.dto.TossConfirmRequest>();
  loseApproval=true;
  for(long user=1;user<=3;user++){
   grantAdmitted(c.getId(),user);String id=UUID.randomUUID().toString();ids.add(id);
   bookings.book(c.getId(),user,id);
   var request=request("automatic-"+user);requests.add(request);
   assertThat(payments.tossConfirm(c.getId(),user,request).getStatus()).isEqualTo(PaymentStatus.PENDING);
  }
  loseApproval=false;failing=true;for(int i=0;i<7;i++)error();failing=false;
  assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
  long start=System.nanoTime();scans.allow.set(true);
  long deadline=start+java.util.concurrent.TimeUnit.SECONDS.toNanos(180);
  while(System.nanoTime()<deadline){
   if(paymentRepository.findAll().stream().allMatch(x->x.getStatus()==PaymentStatus.COMPLETED)&&cb.getState()==CircuitBreaker.State.CLOSED)break;
   Thread.sleep(100);
  }
  assertThat(paymentRepository.findAll()).hasSize(3).allMatch(x->x.getStatus()==PaymentStatus.COMPLETED);
  assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  int scanCount=scans.completed.get();long extra=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
  while(scans.completed.get()<scanCount+2&&System.nanoTime()<extra)Thread.sleep(100);
  assertThat(scans.completed.get()).isGreaterThanOrEqualTo(scanCount+2);
  for(long user=1;user<=3;user++)assertThat(payments.tossConfirm(c.getId(),user,requests.get((int)user-1)).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
  assertThat(approvals).isEqualTo(3);assertThat(cancellations).isZero();
  assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(2);assertThat(outboxRepository.count()).isZero();
  assertThat(bookingRepository.findAll()).hasSize(3).allMatch(x->x.getStatus()==BookingStatus.CONFIRMED);
  for(String id:ids){assertThat(attemptRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);assertThat(redisTemplate.opsForHash().get("booking:attempt:"+id,"state")).isEqualTo("HELD");}
  assertThat(events.stream().anyMatch(x->x.contains("not permitted"))).isTrue();
  events.add("automatic_recovery_ms="+(System.nanoTime()-start)/1000000+" scans="+scans.completed+" threads="+scans.threads);
 }
 @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="circuit.worker.observation",havingValue="true")
 @TestConfiguration static class ObservationConfig {@Bean ScanGate scanGate(){return new ScanGate();}}
 @Aspect static class ScanGate {
  final AtomicBoolean allow=new AtomicBoolean();final AtomicInteger completed=new AtomicInteger();
  final List<String> threads=new java.util.concurrent.CopyOnWriteArrayList<>();
  @Around("execution(* studying.blog.scheduler.PaymentRecoveryWorker.recover(..))")
  Object observe(ProceedingJoinPoint join)throws Throwable{
   while(!allow.get())Thread.sleep(50);
   threads.add(Thread.currentThread().getName());Object result=join.proceed();completed.incrementAndGet();return result;
  }
 }
}
