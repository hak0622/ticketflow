package studying.blog.fault;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import studying.blog.support.IntegrationTestSupport;
import studying.blog.domain.*;
import studying.blog.service.*;
import studying.blog.scheduler.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class RedisLuaPartialWriteIntegrationTest extends IntegrationTestSupport {
 @Autowired BookingAttemptTransactions transactions;
 @Autowired BookingSeatReleaseService recovery;
 @Autowired BookingSeatStore store;
 @Autowired BookingService service;
 @Autowired BookingAttemptRecoveryWorker worker;
 @Autowired BookingExpiryScheduler expiry;
 @Autowired PaymentCompensationScheduler outboxWorker;
 @Autowired PaymentCompensationAdminService admin;
 BookingAttempt attempt; Concert concert;
 @BeforeEach void fixture(){
  concert=savedConcert("partial write",10,0);grantAdmitted(concert.getId(),1L);
  String id=UUID.randomUUID().toString();transactions.create(id,concert.getId(),1L);
  attempt=attemptRepository.findById(id).orElseThrow();
 }
 List<String> keys(){return List.of("seats:concert:"+concert.getId(),"admitted:concert:"+concert.getId()+":user:1","booking:attempt:"+attempt.getId(),"booking:active:concert:"+concert.getId()+":user:1");}
 String source(String name)throws Exception{return new ClassPathResource("redis/"+name+"-booking.lua").getContentAsString(StandardCharsets.UTF_8);}
 void fault(String operation,int boundary)throws Exception{
  String script=source(operation);
  List<String> targets=operation.equals("reserve")?List.of("redis.call('HSET', KEYS[3], 'owner', ARGV[1], 'state', 'HELD'","redis.call('SET', KEYS[4]","redis.call('DECR', KEYS[1])","redis.call('DEL', KEYS[2])","redis.call('HSET', KEYS[3], 'inventoryPhase', 'HELD')"):
   List.of("redis.call('HSET', KEYS[3], 'inventoryVersion'","redis.call('INCR', KEYS[1])","redis.call('DEL', KEYS[4])","  redis.call('SET', KEYS[2]","redis.call('HSET', KEYS[3], 'state', 'RELEASED', 'releaseOutcome', 'RETURNED'");
  String target=targets.get(boundary);var lines=script.lines().toList();assertThat(lines.stream().filter(l->l.startsWith(target)).count()).isEqualTo(1);
  String injected=String.join("\n",lines.stream().map(l->l.startsWith(target)?l+"\nerror('ordinary Lua execution failure')":l).toList());
  Path out=Path.of("test-results/redis-lua-partial-write/current/injections");Files.createDirectories(out);
  Files.writeString(out.resolve(operation+"-"+boundary+".lua"),injected);
  var lua=new DefaultRedisScript<Long>(injected,Long.class);
  assertThatThrownBy(()->redisTemplate.execute(lua,keys(),concert.getId()+":1",attempt.getId(),"1","0")).isInstanceOf(RuntimeException.class).hasStackTraceContaining("ordinary Lua execution failure");
 }
 void due(){jdbcTemplate.update("UPDATE booking_attempt SET next_retry_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),attempt.getId());}
 @ParameterizedTest @CsvSource({"reserve,0","reserve,1","reserve,2","reserve,3","reserve,4","release,0","release,1","release,2","release,3","release,4"})
 void everyWriteBoundary(String operation,int boundary)throws Exception{
  if(operation.equals("release")){
   store.reserve(attempt);attempt.releasePending("fixture return");attemptRepository.saveAndFlush(attempt);
  }
  fault(operation,boundary);long quantity=queueService.getRemainingSeat(concert.getId());
  snapshot("after-fault");
  boolean complete=boundary==4;
  if(!complete){
   assertThatThrownBy(()->store.reserve(attempt)).isInstanceOf(BookingSeatStore.InventoryStateException.class);
   assertThatThrownBy(()->store.release(attempt)).isInstanceOf(BookingSeatStore.InventoryStateException.class);
   assertThatThrownBy(()->store.holdRemaining(attempt)).isInstanceOf(BookingSeatStore.InventoryStateException.class);
   assertThatThrownBy(()->store.isSoldOut(attempt)).isInstanceOf(BookingSeatStore.InventoryStateException.class);
   assertThat(service.book(concert.getId(),1L,attempt.getId()).getStatus()).isEqualTo("BOOKING_PROCESSING");
   due(); // Ensure concurrent recovery really selects the incomplete request.
   ExecutorService pool=Executors.newFixedThreadPool(3);
   try {List<Callable<Void>> tasks=new ArrayList<>();for(int i=0;i<3;i++)tasks.add(()->{recovery.recover(attempt.getId());return null;});for(var result:pool.invokeAll(tasks))result.get();}
   finally{pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
   redisTemplate.opsForHash().put(keys().get(2),"holdExpiresAt","1");due();worker.recoverIncompleteRequests();
   assertThat(bookingRepository.count()).isZero();assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(quantity);
   // A different customer's inventory activity must not be used to infer this request's outcome.
   grantAdmitted(concert.getId(),2L);service.book(concert.getId(),2L,UUID.randomUUID().toString());
   long afterOther=queueService.getRemainingSeat(concert.getId());recovery.recover(attempt.getId());assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(afterOther);
  }else{
   due();worker.recoverIncompleteRequests();
   assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus()).isEqualTo(operation.equals("reserve")?BookingAttemptStatus.BOOKED:BookingAttemptStatus.RELEASED);
   assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(quantity);
  }
 }
 @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"inventoryVersion","inventoryPhase"})
 void damagedProgressIsNotLegacy(String field){store.reserve(attempt);redisTemplate.opsForHash().delete(keys().get(2),field);assertThatThrownBy(()->store.holdRemaining(attempt)).isInstanceOf(BookingSeatStore.InventoryStateException.class);due();worker.recoverIncompleteRequests();assertThat(bookingRepository.count()).isZero();assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(9);}
 @Test void outboxExpiryAndAdminCannotBypassPartialReturn()throws Exception{
  service.book(concert.getId(),1L,attempt.getId());var booking=bookingRepository.findByConcertIdAndUserId(concert.getId(),1L).orElseThrow();
  var payment=Payment.create(booking,1L,concert.getId(),90000,"CARD",UUID.randomUUID().toString());payment.fail("fixture");paymentRepository.saveAndFlush(payment);
  var event=outboxRepository.saveAndFlush(PaymentCompensationOutbox.create("{\"bookingId\":"+booking.getId()+",\"concertId\":"+concert.getId()+",\"paymentId\":"+payment.getId()+"}"));
  transactions.cancel(booking.getId(),null,payment.getId());fault("release",1);
  long quantity=queueService.getRemainingSeat(concert.getId());
  for(int i=0;i<3;i++)outboxWorker.processPending();assertThat(outboxRepository.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.FAILED);
  admin.retry(event.getId());outboxWorker.processPending();
  jdbcTemplate.update("UPDATE booking SET created_at=? WHERE id=?",LocalDateTime.now().minusMinutes(31),booking.getId());expiry.expireStaleBookings();due();worker.recoverIncompleteRequests();
  assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(quantity);assertThat(outboxRepository.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
  assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.RELEASE_PENDING);
 }
 @Test void unsupportedVersionAndInvalidCombinationAreBlocked(){
  store.reserve(attempt);
  redisTemplate.opsForHash().put(keys().get(2),"inventoryVersion","99");
  assertThatThrownBy(()->store.reserve(attempt)).isInstanceOf(BookingSeatStore.InventoryStateException.class);
  redisTemplate.opsForHash().put(keys().get(2),"inventoryVersion","1");
  redisTemplate.opsForHash().put(keys().get(2),"inventoryPhase","RETURNED");
  assertThatThrownBy(()->store.isSoldOut(attempt)).isInstanceOf(BookingSeatStore.InventoryStateException.class);
  due();worker.recoverIncompleteRequests();assertThat(bookingRepository.count()).isZero();
 }
 @Test void prewriteValidationDoesNotChangeData(){
  redisTemplate.opsForValue().set(keys().get(0),"invalid");
  assertThatThrownBy(()->store.reserve(attempt)).isInstanceOf(RuntimeException.class);
  assertThat(redisTemplate.hasKey(keys().get(2))).isFalse();assertThat(queueService.isAdmitted(concert.getId(),1L)).isTrue();
  redisTemplate.opsForValue().set(keys().get(0),"10");store.reserve(attempt);
  redisTemplate.opsForValue().set(keys().get(0),"999999999");
  assertThatThrownBy(()->store.release(attempt)).isInstanceOf(RuntimeException.class);
  assertThat(redisTemplate.opsForHash().get(keys().get(2),"inventoryPhase")).isEqualTo("HELD");
  assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(999999999L);
 }
 void snapshot(String label)throws Exception{
  Path folder=Path.of("test-results/redis-lua-partial-write/current/snapshots");Files.createDirectories(folder);
  var redis=new TreeMap<String,Object>();
  for(String key:keys()){
   var type=redisTemplate.type(key);
   if(type==org.springframework.data.redis.connection.DataType.HASH)redis.put(key,redisTemplate.opsForHash().entries(key));
   else if(type==org.springframework.data.redis.connection.DataType.STRING)redis.put(key,redisTemplate.opsForValue().get(key));
  }
  var payload=Map.of("redis",redis,"attempt",jdbcTemplate.queryForList("SELECT * FROM booking_attempt WHERE id=?",attempt.getId()),"bookings",jdbcTemplate.queryForList("SELECT id,status FROM booking"),"outbox",jdbcTemplate.queryForList("SELECT id,status,retry_count FROM payment_compensation_outbox"));
  Files.writeString(folder.resolve(attempt.getId()+"-"+label+".json"),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(payload));
 }
 @AfterEach void preserveResult()throws Exception{snapshot("final");}

}
