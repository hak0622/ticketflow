package studying.blog.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import studying.blog.config.TokenProvider;
import studying.blog.domain.*;
import studying.blog.repository.UserRepository;
import studying.blog.scheduler.ConcertQueueScheduler;
import studying.blog.service.ConcertService;
import studying.blog.support.IntegrationTestSupport;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Queue invariants after the fix; original characterization sources/results are preserved separately. */
@Tag("integration")
@AutoConfigureMockMvc
class QueueConsistencyIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired TokenProvider tokens;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    @Autowired ConcertService concerts;
    @Autowired ConcertQueueScheduler scheduler;
    StringRedisTemplate original;
    final List<Map<String,Object>> events=new CopyOnWriteArrayList<>();
    final Set<Long> concertIds=new LinkedHashSet<>();
    final Set<Long> userIds=ConcurrentHashMap.newKeySet();
    final List<ExecutorService> pools=new ArrayList<>();
    volatile Consumer<Add> beforeAdd=a -> {};
    volatile Consumer<Add> afterAdd=a -> {};
    record Add(String key,String member,double score) {}

    @BeforeEach @SuppressWarnings("unchecked") void installObservation() {
        original=(StringRedisTemplate) ReflectionTestUtils.getField(queueService,"redisTemplate");
        var template=spy(original);
        doAnswer(inv -> {
            RedisScript<?> script=inv.getArgument(0);
            List<String> keys=inv.getArgument(1);
            Object[] args=Arrays.copyOfRange(inv.getArguments(),2,inv.getArguments().length);
            if(script.getScriptAsString().contains("Invalid queue score")) {
                var add=new Add(keys.get(0),args[0].toString(),Double.parseDouble(args[1].toString()));
                event("before-registration-lua",add);beforeAdd.accept(add);
                Object result=inv.callRealMethod();
                event("registration-lua-result",result);afterAdd.accept(add);return result;
            }
            Object result=inv.callRealMethod();event("queue-lua-result",Map.of("keys",keys,"result",result));
            return result;
        }).when(template).execute(any(RedisScript.class),anyList(),any(Object[].class));
        ReflectionTestUtils.setField(queueService,"redisTemplate",template);
    }
    void event(String type,Object value) { events.add(Map.of("event",type,"value",value,"thread",Thread.currentThread().getName(),"nanoTime",System.nanoTime())); }
    Concert concert() { var c=savedConcert("queue-check",400,0); concertIds.add(c.getId()); return c; }
    User user() {
        String suffix=UUID.randomUUID().toString();
        var u=users.save(User.builder().email(suffix+"@test.invalid").nickname(suffix).role(Role.USER).build());
        userIds.add(u.getId()); return u;
    }
    String queue(Long cid) { return "queue:concert:"+cid; }
    String ticket(Long cid,long uid) { return "admitted:concert:"+cid+":user:"+uid; }
    Map<String,Object> http(String verb,Long cid,User u) throws Exception {
        var request=verb.equals("POST")?post("/api/concerts/{id}/queue",cid):get("/api/concerts/{id}/queue/me",cid);
        if(u!=null) request.header("Authorization","Bearer "+tokens.generateToken(u,Duration.ofMinutes(10)));
        var result=mvc.perform(request).andReturn();
        var body=json.readValue(result.getResponse().getContentAsString().isBlank()?"{}":result.getResponse().getContentAsString(),Map.class);
        event("http",Map.of("method",verb,"concert",cid,"status",result.getResponse().getStatus(),"body",body));
        return Map.of("http",result.getResponse().getStatus(),"body",body);
    }
    Map<?,?> body(Map<String,Object> reply) { return (Map<?,?>) reply.get("body"); }
    void waitTick(double score) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(Instant.now().toEpochMilli()<=score && System.nanoTime()<end) Thread.yield();
        assertThat((double)Instant.now().toEpochMilli()).isGreaterThan(score);
    }
    void await(CountDownLatch latch) {
        try { assertThat(latch.await(5,TimeUnit.SECONDS)).as("gate arrived/released").isTrue(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    ExecutorService pool(int n) { var p=Executors.newFixedThreadPool(n); pools.add(p); return p; }

    @Test void reregisterPreservesScoreAndOriginalPosition() throws Exception {
        var c=concert(); var a=user();var b=user();
        http("POST",c.getId(),a);
        double first=original.opsForZSet().score(queue(c.getId()),a.getId().toString()); waitTick(first);
        http("POST",c.getId(),b);
        waitTick(original.opsForZSet().score(queue(c.getId()),b.getId().toString()));
        var response=http("POST",c.getId(),a);
        assertThat(original.opsForZSet().zCard(queue(c.getId()))).isEqualTo(2);
        assertThat(original.opsForZSet().score(queue(c.getId()),a.getId().toString())).isEqualTo(first);
        assertThat(body(response).get("position")).isEqualTo(1);
        assertThat(queueService.getPosition(c.getId(),b.getId())).isEqualTo(2);
    }
    @Test void olderCalculatedScoreCannotOverwriteFirstCommittedRegistration() throws Exception {
        var c=concert();var a=user(); var ready=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        var captured=new java.util.concurrent.atomic.AtomicReference<Double>();
        beforeAdd=add -> { if(Thread.currentThread().getName().contains("older")) { captured.set(add.score());ready.countDown();await(proceed); } };
        var p=pool(1);var older=p.submit(() -> {Thread.currentThread().setName("older-register");return http("POST",c.getId(),a);});
        try {
            await(ready);waitTick(captured.get());http("POST",c.getId(),a);
            double newer=original.opsForZSet().score(queue(c.getId()),a.getId().toString());
            proceed.countDown();older.get(5,TimeUnit.SECONDS);
            assertThat(original.opsForZSet().score(queue(c.getId()),a.getId().toString())).isEqualTo(newer).isGreaterThan(captured.get());
            assertThat(original.opsForZSet().zCard(queue(c.getId()))).isEqualTo(1);
        } finally {proceed.countDown();}
    }
    @Test void admissionReregistrationKeepsAdmissionAndOriginalExpiry() throws Exception {
        var c=concert();var a=user();grantAdmitted(c.getId(),a.getId());
        original.expire(ticket(c.getId(),a.getId()),Duration.ofSeconds(60));
        long before=original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS);
        long deadline=ticketDeadline(c.getId(),a.getId());
        event("admission-pttl-before-reregistration",before);
        assertThat(body(http("POST",c.getId(),a)).get("status")).isEqualTo("ADMITTED");
        assertThat(original.opsForZSet().zCard(queue(c.getId()))).isZero();
        assertThat(body(http("GET",c.getId(),a)).get("status")).isEqualTo("ADMITTED");
        assertThat(queueService.popAndGrantAdmitted(c.getId(),1,600)).isEmpty();
        assertThat(original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS)).isBetween(1L,before);
        assertThat(ticketDeadline(c.getId(),a.getId())).isEqualTo(deadline);
        event("admission-pttl-after",original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS));
    }
    @ParameterizedTest @EnumSource(BookingStatus.class)
    void bookingStateDoesNotPreventReregistration(BookingStatus state) throws Exception {
        var c=concert();var a=user();grantAdmitted(c.getId(),a.getId());
        bookingServiceBook(c,a);
        jdbcTemplate.update("update booking set status=? where concert_id=?",state.name(),c.getId());
        var response=http("POST",c.getId(),a);assertThat(body(response).get("status")).isEqualTo("QUEUED");
        assertThat(queueService.getPosition(c.getId(),a.getId())).isEqualTo(1);
        assertThat(original.opsForZSet().score(queue(c.getId()),a.getId().toString())).isNotNull();
        assertThat(queueService.popAndGrantAdmitted(c.getId(),1,600)).containsExactly(a.getId().toString());
        assertThat(body(http("GET",c.getId(),a)).get("status")).isEqualTo("ADMITTED");
        assertThat(queueService.isAdmitted(c.getId(),a.getId())).isTrue();
        assertThat(bookingRepository.findByConcertIdAndUserId(c.getId(),a.getId()).orElseThrow().getStatus()).isEqualTo(state);
        assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(399);
    }
    @Autowired studying.blog.service.BookingService bookingService;
    void bookingServiceBook(Concert c,User u) {bookingService.book(c.getId(),u.getId(),UUID.randomUUID().toString());}
    @Test void grantAfterAtomicRegistrationNeverCreatesQueuedZero() throws Exception {
        var c=concert();var a=user();var added=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        afterAdd=add -> {event("gate-after-real-registration-lua",add);added.countDown();await(proceed);};
        var task=pool(1).submit(() -> http("POST",c.getId(),a));
        try {
            await(added);event("pop-completed",queueService.popAndGrantAdmitted(c.getId(),1,600));
            proceed.countDown();var reply=task.get(5,TimeUnit.SECONDS);
            assertThat(reply.get("http")).isEqualTo(200);
            assertThat(body(reply).get("status")).isEqualTo("QUEUED");assertThat(body(reply).get("position")).isEqualTo(1);
            assertThat(body(http("GET",c.getId(),a)).get("status")).isEqualTo("ADMITTED");
        } finally {proceed.countDown();}
    }
    @ParameterizedTest @ValueSource(ints={0,17,350})
    void threeConcurrentPopsAreDisjointOnlyForFixedQueue(int size) throws Exception {
        var c=concert();
        for(int i=1;i<=size;i++){userIds.add((long)i);original.opsForZSet().add(queue(c.getId()),""+i,i);}
        var start=new CountDownLatch(1);var ready=new CountDownLatch(3);var p=pool(3);
        List<Future<List<String>>> work=new ArrayList<>();
        for(int i=0;i<3;i++)work.add(p.submit(() -> {ready.countDown();await(start);var selected=queueService.popAndGrantAdmitted(c.getId(),100,600);event("pop",selected);return selected;}));
        try {
            await(ready);start.countDown();List<String> all=new ArrayList<>();
            for(var f:work){var ids=f.get(5,TimeUnit.SECONDS);assertThat(ids.size()).isLessThanOrEqualTo(100);all.addAll(ids);}
            int expected=Math.min(300,size);assertThat(all).hasSize(expected).doesNotHaveDuplicates();
            assertThat(original.opsForZSet().zCard(queue(c.getId()))).isEqualTo(size-expected);
            for(String uid:all)assertThat(queueService.isAdmitted(c.getId(),Long.valueOf(uid))).isTrue();
        } finally {start.countDown();}
    }
    @Test void sameScoreUsesLexicalOrderAndScoresCanOverrideArrivalOrder() {
        var c=concert();original.opsForZSet().add(queue(c.getId()),"2",100);original.opsForZSet().add(queue(c.getId()),"10",100);
        assertThat(original.opsForZSet().range(queue(c.getId()),0,-1)).containsExactly("10","2");
        original.opsForZSet().add(queue(c.getId()),"later-arrival",99);
        assertThat(original.opsForZSet().range(queue(c.getId()),0,-1)).containsExactly("later-arrival","10","2");
    }
    @Test void popThenReregisterDoesNotGrantSameAdmissionAgain() throws Exception {
        var c=concert();var a=user();http("POST",c.getId(),a);
        assertThat(queueService.popAndGrantAdmitted(c.getId(),1,600)).containsExactly(a.getId().toString());
        long ttl=original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS);
        long deadline=ticketDeadline(c.getId(),a.getId());
        assertThat(body(http("POST",c.getId(),a)).get("status")).isEqualTo("ADMITTED");
        assertThat(queueService.popAndGrantAdmitted(c.getId(),1,600)).isEmpty();
        assertThat(original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS)).isBetween(1L,ttl);
        assertThat(ticketDeadline(c.getId(),a.getId())).isEqualTo(deadline);
    }
    @ParameterizedTest @CsvSource({"CLOSED,false","CLOSED,true","SOLD_OUT,false","SOLD_OUT,true","ENDED,false","ENDED,true"})
    void unavailableConcertUsesActual403Mapping(String state,boolean hit) throws Exception {
        var c=concert();var a=user();
        var status=state.equals("ENDED")?ConcertStatus.OPEN:ConcertStatus.valueOf(state);
        var at=state.equals("ENDED")?LocalDateTime.now().minusDays(1):LocalDateTime.now().plusDays(1);
        jdbcTemplate.update("update concert set status=?,event_at=? where id=?",status.name(),at,c.getId());
        if(hit)queueService.setConcertInfo(c.getId(),status,c.getTitle(),at);
        var response=http("POST",c.getId(),a);
        assertThat(response.get("http")).isEqualTo(403);
        assertThat(original.opsForZSet().zCard(queue(c.getId()))).isZero();
    }
    @Test void adminCommitRefreshesOpenCacheAndSchedulerSkipsClosedSoldOutAndEnded() throws Exception {
        var a=user();var closed=concert();var sold=concert();var ended=concert();var open=concert();
        queueService.setConcertInfo(closed.getId(),ConcertStatus.OPEN,closed.getTitle(),closed.getEventAt());
        concerts.adminClose(closed.getId());
        assertThat(queueService.getConcertInfo(closed.getId()).status()).isEqualTo(ConcertStatus.CLOSED);
        assertThat(http("POST",closed.getId(),a).get("http")).isEqualTo(403);
        jdbcTemplate.update("update concert set status='SOLD_OUT' where id=?",sold.getId());
        jdbcTemplate.update("update concert set event_at=? where id=?",LocalDateTime.now().minusDays(1),ended.getId());
        for(var c:List.of(closed,sold,ended,open))original.opsForZSet().add(queue(c.getId()),a.getId().toString(),1);
        long started=System.nanoTime();
        scheduler.processQueue();
        for(var c:List.of(closed,sold,ended)) {
            assertThat(queueService.getPosition(c.getId(),a.getId())).isEqualTo(1);
            assertThat(queueService.isAdmitted(c.getId(),a.getId())).isFalse();
        }
        assertThat(queueService.isAdmitted(open.getId(),a.getId())).isTrue();
        assertTicketWindow(open.getId(),a.getId(),started);
    }
    @Test void staleOpenFixtureCacheIsDistinctFromAdminChangePath() throws Exception {
        var c=concert();var a=user();queueService.setConcertInfo(c.getId(),ConcertStatus.OPEN,c.getTitle(),c.getEventAt());
        jdbcTemplate.update("update concert set status='CLOSED' where id=?",c.getId());
        assertThat(http("POST",c.getId(),a).get("http")).isEqualTo(200);
        queueService.deleteConcertStatus(c.getId());
        assertThat(http("POST",c.getId(),a).get("http")).isEqualTo(403);
    }
    @Test void authenticationMissingConcertAndGetDoesNotValidateConcertAvailability() throws Exception {
        var c=concert();var a=user();
        assertThat(http("POST",c.getId(),null).get("http")).isEqualTo(401);
        assertThat(http("GET",c.getId(),null).get("http")).isEqualTo(401);
        long missing=Long.MAX_VALUE;
        assertThat(http("POST",missing,a).get("http")).isEqualTo(400);
        assertThat(body(http("GET",missing,a)).get("status")).isEqualTo("NOT_IN_QUEUE");
        concerts.adminClose(c.getId());
        assertThat(body(http("GET",c.getId(),a)).get("status")).isEqualTo("NOT_IN_QUEUE");
    }
    @Test void pollingBoundaryAndGetReadOnlyAndIsolation() throws Exception {
        var c=concert();var other=concert();var a=user();var b=user();
        original.opsForZSet().add(queue(c.getId()),a.getId().toString(),1000);
        original.opsForZSet().add(queue(c.getId()),b.getId().toString(),1001);
        for(int i=1;i<1000;i++)original.opsForZSet().add(queue(c.getId()),"filler-"+i,i);
        grantAdmitted(other.getId(),a.getId());
        var queueBefore=original.opsForZSet().rangeWithScores(queue(c.getId()),0,-1);
        var dbBefore=jdbcTemplate.queryForList("select * from concert order by id");
        long ttl=original.getExpire(ticket(other.getId(),a.getId()),TimeUnit.MILLISECONDS);
        assertThat(body(http("GET",c.getId(),a)).get("nextPollMs")).isEqualTo(5000);
        assertThat(body(http("GET",c.getId(),b)).get("nextPollMs")).isEqualTo(10000);
        assertThat(body(http("GET",other.getId(),a)).get("status")).isEqualTo("ADMITTED");
        assertThat(body(http("GET",other.getId(),b)).get("status")).isEqualTo("NOT_IN_QUEUE");
        assertThat(original.opsForZSet().rangeWithScores(queue(c.getId()),0,-1)).isEqualTo(queueBefore);
        assertThat(jdbcTemplate.queryForList("select * from concert order by id")).isEqualTo(dbBefore);
        assertThat(original.getExpire(ticket(other.getId(),a.getId()),TimeUnit.MILLISECONDS)).isBetween(1L,ttl);
        assertThat(original.opsForValue().get(ticket(other.getId(),a.getId()))).isEqualTo("1");
        assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(400);
        assertThat(bookingRepository.count()).isZero();assertThat(attemptRepository.count()).isZero();assertThat(paymentRepository.count()).isZero();assertThat(outboxRepository.count()).isZero();
    }
    @Test void getWithExistingHeldBookingDoesNotConsumeOrModifyInventory() throws Exception {
        var c=concert();var a=user();grantAdmitted(c.getId(),a.getId());bookingServiceBook(c,a);
        var booking=bookingRepository.findByConcertIdAndUserId(c.getId(),a.getId()).orElseThrow();
        var attempt=attemptRepository.findByBookingId(booking.getId()).orElseThrow();
        String hash="booking:attempt:"+attempt.getId();
        String active="booking:active:concert:"+c.getId()+":user:"+a.getId();
        var held=original.opsForHash().entries(hash);var activeBefore=original.opsForValue().get(active);
        var db=jdbcTemplate.queryForList("select * from booking_attempt order by id");
        assertThat(body(http("GET",c.getId(),a)).get("status")).isEqualTo("NOT_IN_QUEUE");
        assertThat(original.opsForHash().entries(hash)).isEqualTo(held);
        assertThat(original.opsForValue().get(active)).isEqualTo(activeBefore);
        assertThat(jdbcTemplate.queryForList("select * from booking_attempt order by id")).isEqualTo(db);
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(399);
        assertThat(paymentRepository.count()).isZero();assertThat(outboxRepository.count()).isZero();
    }
    @Test void coexistenceGetIsReadOnlyAndPostCleansQueueWithoutRefreshingTicket() throws Exception {
        var c=concert();var a=user();grantAdmitted(c.getId(),a.getId());
        original.expire(ticket(c.getId(),a.getId()),Duration.ofSeconds(60));
        original.opsForZSet().add(queue(c.getId()),a.getId().toString(),123);
        long ttl=original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS);
        long deadline=ticketDeadline(c.getId(),a.getId());
        assertThat(body(http("GET",c.getId(),a)).get("status")).isEqualTo("ADMITTED");
        assertThat(original.opsForZSet().score(queue(c.getId()),a.getId().toString())).isEqualTo(123);
        assertThat(body(http("POST",c.getId(),a)).get("status")).isEqualTo("ADMITTED");
        assertThat(original.opsForZSet().score(queue(c.getId()),a.getId().toString())).isNull();
        assertThat(original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS)).isBetween(1L,ttl);
        assertThat(ticketDeadline(c.getId(),a.getId())).isEqualTo(deadline);
    }
    @Test void popSkipsExistingAdmissionWithoutRefillAndCountsOnlyNewGrants() {
        var c=concert();var a=user();var b=user();var next=user();
        original.opsForZSet().add(queue(c.getId()),a.getId().toString(),1);
        original.opsForZSet().add(queue(c.getId()),b.getId().toString(),2);
        original.opsForZSet().add(queue(c.getId()),next.getId().toString(),3);
        grantAdmitted(c.getId(),a.getId());original.expire(ticket(c.getId(),a.getId()),Duration.ofSeconds(60));
        long ttl=original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS);
        long deadline=ticketDeadline(c.getId(),a.getId());
        long started=System.nanoTime();
        assertThat(queueService.popAndGrantAdmitted(c.getId(),2,600)).containsExactly(b.getId().toString());
        assertTicketWindow(c.getId(),b.getId(),started);
        assertThat(original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS)).isBetween(1L,ttl);
        assertThat(ticketDeadline(c.getId(),a.getId())).isEqualTo(deadline);
        assertThat(original.opsForZSet().range(queue(c.getId()),0,-1)).containsExactly(next.getId().toString());
        assertThat(queueService.isAdmitted(c.getId(),next.getId())).isFalse();
    }
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Test void schedulerCountsNewGrantsInsteadOfRemovedQueueMembers() {
        var c=concert();var a=user();var b=user();
        original.opsForZSet().add(queue(c.getId()),a.getId().toString(),1);
        original.opsForZSet().add(queue(c.getId()),b.getId().toString(),2);
        grantAdmitted(c.getId(),a.getId());original.expire(ticket(c.getId(),a.getId()),Duration.ofSeconds(60));
        double before=meters.counter("scheduler.queue.granted").count();
        long ttl=original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS);
        long deadline=ticketDeadline(c.getId(),a.getId());
        scheduler.processQueue();
        assertThat(meters.counter("scheduler.queue.granted").count()-before).isEqualTo(1);
        assertThat(original.opsForZSet().zCard(queue(c.getId()))).isZero();
        assertThat(original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS)).isBetween(1L,ttl);
        assertThat(ticketDeadline(c.getId(),a.getId())).isEqualTo(deadline);
    }
    @Test void corruptLaterAdmissionFailsBeforeAnyBatchMutation() {
        var c=concert();var a=user();var b=user();
        original.opsForZSet().add(queue(c.getId()),a.getId().toString(),1);
        original.opsForZSet().add(queue(c.getId()),b.getId().toString(),2);
        original.opsForList().rightPush(ticket(c.getId(),b.getId()),"corrupt");
        var before=original.opsForZSet().rangeWithScores(queue(c.getId()),0,-1);
        assertThatThrownBy(() -> queueService.popAndGrantAdmitted(c.getId(),2,600)).hasStackTraceContaining("Invalid admission type");
        assertThat(original.opsForZSet().rangeWithScores(queue(c.getId()),0,-1)).isEqualTo(before);
        assertThat(original.hasKey(ticket(c.getId(),a.getId()))).isFalse();
        assertThat(original.opsForList().range(ticket(c.getId(),b.getId()),0,-1)).containsExactly("corrupt");
    }
    @ParameterizedTest @CsvSource({"0,600","-1,600","1,0","1,-1"})
    void invalidBatchArgumentsFailWithoutMutation(int count,int ttl) {
        var c=concert();var a=user();original.opsForZSet().add(queue(c.getId()),a.getId().toString(),1);
        assertThatThrownBy(() -> queueService.popAndGrantAdmitted(c.getId(),count,ttl)).isInstanceOf(RuntimeException.class);
        assertThat(queueService.getPosition(c.getId(),a.getId())).isEqualTo(1);
        assertThat(queueService.isAdmitted(c.getId(),a.getId())).isFalse();
    }
    @Test void shortAndPersistentAdmissionsKeepTheirExistingQueueCompatibility() throws Exception {
        var c=concert();var shortTicket=user();var persistent=user();
        original.opsForValue().set(ticket(c.getId(),shortTicket.getId()),"1",Duration.ofSeconds(2));
        original.opsForValue().set(ticket(c.getId(),persistent.getId()),"1");
        for(var a:List.of(shortTicket,persistent)) {
            assertThat(body(http("POST",c.getId(),a)).get("status")).isEqualTo("ADMITTED");
            assertThat(body(http("GET",c.getId(),a)).get("status")).isEqualTo("ADMITTED");
        }
        assertThat(original.getExpire(ticket(c.getId(),shortTicket.getId()),TimeUnit.MILLISECONDS)).isBetween(1L,2000L);
        assertThat(original.getExpire(ticket(c.getId(),persistent.getId()),TimeUnit.MILLISECONDS)).isEqualTo(-1);
        assertThat(original.opsForZSet().zCard(queue(c.getId()))).isZero();
    }
    @Test void expiredAdmissionAllowsFreshQueueRegistrationWithoutRestoringOldRank() throws Exception {
        var c=concert();var a=user();var b=user();
        grantAdmitted(c.getId(),a.getId());
        original.expireAt(ticket(c.getId(),a.getId()),Instant.now().minusSeconds(1));
        http("POST",c.getId(),b);
        double score=original.opsForZSet().score(queue(c.getId()),b.getId().toString());waitTick(score);
        var result=http("POST",c.getId(),a);
        assertThat(body(result).get("status")).isEqualTo("QUEUED");
        assertThat(body(result).get("position")).isEqualTo(2);
    }
    @Test void grantBeforeRegistrationLuaReturnsAdmitted() throws Exception {
        var c=concert();var a=user();
        original.opsForZSet().add(queue(c.getId()),a.getId().toString(),1);
        var ready=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        beforeAdd=add -> {ready.countDown();await(proceed);};
        var task=pool(1).submit(() -> http("POST",c.getId(),a));
        try {
            await(ready);assertThat(queueService.popAndGrantAdmitted(c.getId(),1,600)).containsExactly(a.getId().toString());
            long ttl=original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS);
        long deadline=ticketDeadline(c.getId(),a.getId());
            proceed.countDown();var reply=task.get(5,TimeUnit.SECONDS);
            assertThat(body(reply).get("status")).isEqualTo("ADMITTED");
            assertThat(body(reply).containsKey("position")).isFalse();
            assertThat(original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS)).isBetween(1L,ttl);
        assertThat(ticketDeadline(c.getId(),a.getId())).isEqualTo(deadline);
        } finally {proceed.countDown();}
    }
    @Test void concurrentRegisterReadAndPopOnlyReturnCoherentSnapshots() throws Exception {
        var c=concert();var a=user();var start=new CountDownLatch(1);var p=pool(3);
        var register=p.submit(() -> {await(start);for(int i=0;i<30;i++)assertSnapshot(queueService.register(c.getId(),a.getId()));return true;});
        var read=p.submit(() -> {await(start);for(int i=0;i<30;i++)assertSnapshot(queueService.readQueue(c.getId(),a.getId()));return true;});
        var pop=p.submit(() -> {await(start);int grants=0;for(int i=0;i<30;i++)grants+=queueService.popAndGrantAdmitted(c.getId(),1,600).size();return grants;});
        start.countDown();assertThat(register.get(10,TimeUnit.SECONDS)).isTrue();assertThat(read.get(10,TimeUnit.SECONDS)).isTrue();
        assertThat(pop.get(10,TimeUnit.SECONDS)).isBetween(0,1);
        assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(400);
    }
    void assertSnapshot(studying.blog.service.QueueService.QueueSnapshot snapshot) {
        assertThat(snapshot.status()).isIn("QUEUED","ADMITTED","NOT_IN_QUEUE");
        if(snapshot.status().equals("QUEUED"))assertThat(snapshot.position()).isBetween(1L,snapshot.total());
        else assertThat(snapshot.position()).isNull();
    }
    @Test void registrationCompletedThenJavaLosesResultReusesScoreOnRetry() {
        var c=concert();var a=user();var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        afterAdd=add -> {if(first.getAndSet(false))throw new org.springframework.data.redis.RedisSystemException("test result loss after actual Lua",new IllegalStateException("injected"));};
        assertThatThrownBy(() -> queueService.register(c.getId(),a.getId())).isInstanceOf(org.springframework.data.redis.RedisSystemException.class);
        double score=original.opsForZSet().score(queue(c.getId()),a.getId().toString());waitTick(score);
        assertThat(queueService.register(c.getId(),a.getId()).position()).isEqualTo(1);
        assertThat(original.opsForZSet().score(queue(c.getId()),a.getId().toString())).isEqualTo(score);
    }
    @Test @SuppressWarnings("unchecked") void grantCompletedThenJavaLosesResultDoesNotRegrantOrExtendExpiry() {
        var c=concert();var a=user();queueService.register(c.getId(),a.getId());
        var template=(StringRedisTemplate)ReflectionTestUtils.getField(queueService,"redisTemplate");
        var once=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(inv -> {
            Object result=inv.callRealMethod();
            if(once.getAndSet(false))throw new org.springframework.data.redis.RedisSystemException("test result loss after actual Lua",new IllegalStateException("injected"));
            return result;
        }).when(template).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertThatThrownBy(() -> queueService.popAndGrantAdmitted(c.getId(),1,600)).isInstanceOf(org.springframework.data.redis.RedisSystemException.class);
        long ttl=original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS);
        long deadline=ticketDeadline(c.getId(),a.getId());
        assertThat(queueService.popAndGrantAdmitted(c.getId(),1,600)).isEmpty();
        assertThat(original.getExpire(ticket(c.getId(),a.getId()),TimeUnit.MILLISECONDS)).isBetween(1L,ttl);
        assertThat(ticketDeadline(c.getId(),a.getId())).isEqualTo(deadline);
        assertThat(queueService.readQueue(c.getId(),a.getId()).status()).isEqualTo("ADMITTED");
    }
    @Test void invalidAdmissionTypeNeverBecomesQueuedOrNotInQueue() {
        var c=concert();var a=user();original.opsForList().rightPush(ticket(c.getId(),a.getId()),"bad");
        assertThatThrownBy(() -> queueService.register(c.getId(),a.getId())).hasStackTraceContaining("Invalid admission type");
        assertThatThrownBy(() -> queueService.readQueue(c.getId(),a.getId())).hasStackTraceContaining("Invalid admission type");
        assertThat(original.opsForZSet().zCard(queue(c.getId()))).isZero();
    }
    @Test void invalidQueueTypeNeverBecomesNormalResponse() {
        var c=concert();var a=user();original.opsForValue().set(queue(c.getId()),"bad");
        assertThatThrownBy(() -> queueService.register(c.getId(),a.getId())).hasStackTraceContaining("Invalid queue type");
        assertThatThrownBy(() -> queueService.readQueue(c.getId(),a.getId())).hasStackTraceContaining("Invalid queue type");
        assertThat(original.opsForValue().get(queue(c.getId()))).isEqualTo("bad");
    }
    @Test @SuppressWarnings("unchecked") void malformedOrMissingRedisSnapshotsAreNotConvertedToZeroRank() {
        var c=concert();var a=user();
        var template=(StringRedisTemplate)ReflectionTestUtils.getField(queueService,"redisTemplate");
        for(List<?> invalid:Arrays.asList(List.of(1L,0L,1L),List.of(1L,2L,1L),List.of(9L,0L,0L),List.of("bad",0L,0L),List.of(),null)) {
            doReturn(invalid).when(template).execute(any(RedisScript.class),anyList(),any(Object[].class));
            assertThatThrownBy(() -> queueService.readQueue(c.getId(),a.getId())).isInstanceOf(IllegalStateException.class).hasMessage("Invalid Redis queue response");
        }
        assertThat(original.opsForZSet().zCard(queue(c.getId()))).isZero();
    }
    long ticketDeadline(Long cid,long uid) {
        Long deadline=original.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "return redis.call('PEXPIRETIME', KEYS[1])",Long.class),List.of(ticket(cid,uid)));
        event("admission-absolute-expiry",Map.of("concert",cid,"user",uid,"deadline",Objects.requireNonNull(deadline)));
        return deadline;
    }
    void assertTicketWindow(Long cid,long uid,long started) {
        long pttl=original.getExpire(ticket(cid,uid),TimeUnit.MILLISECONDS);
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertThat(elapsed).as("bounded TTL observation window").isLessThan(5000);
        assertThat(pttl).isBetween(600000L-elapsed-1000,600000L);
        event("600-second-contract",Map.of("pttl",pttl,"elapsedMs",elapsed));
    }
    @AfterEach void preserveEvidence(TestInfo info) throws Exception {
        beforeAdd=a -> {};afterAdd=a -> {};
        List<Throwable> errors=new ArrayList<>();
        try {
            for(var p:pools) {
                try {
                    p.shutdown();
                    if(!p.awaitTermination(10,TimeUnit.SECONDS)) {
                        p.shutdownNow();
                        if(!p.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("test executor still active after cancellation");
                        throw new IllegalStateException("test executor required forced cancellation");
                    }
                } catch(Exception failure) {p.shutdownNow();errors.add(failure);}
            }
        } finally {
            try {ReflectionTestUtils.setField(queueService,"redisTemplate",original);}
            catch(Exception failure) {errors.add(failure);}
        }
        try {writeEvidence(info);} catch(Exception failure) {errors.add(failure);}
        if(!errors.isEmpty()) {
            Path dir=resultRoot().resolve("cleanup-errors");Files.createDirectories(dir);
            Files.writeString(dir.resolve(UUID.randomUUID()+".txt"),errors.toString());
            var error=new AssertionError("Test cleanup/evidence failed; see cleanup-errors");
            errors.forEach(error::addSuppressed);throw error;
        }
    }
    Path resultRoot() throws Exception {
        Path marker=Path.of("test-results/queue-consistency-fix/current-run.txt");
        return Files.exists(marker)?Path.of(Files.readString(marker).trim())
                :Path.of("test-results/queue-consistency-fix/current");
    }
    void writeEvidence(TestInfo info) throws Exception {
        var state=new LinkedHashMap<String,Object>();state.put("events",events);
        for(String table:List.of("concert","booking","booking_attempt","payment","payment_compensation_outbox"))state.put(table,jdbcTemplate.queryForList("select * from "+table));
        var redis=new LinkedHashMap<String,Object>();
        for(Long cid:concertIds) {
            var queueType=original.type(queue(cid));
            redis.put(queue(cid),queueType==org.springframework.data.redis.connection.DataType.ZSET
                    ?original.opsForZSet().rangeWithScores(queue(cid),0,-1):String.valueOf(queueType));
            redis.put("remaining:"+cid,queueService.getRemainingSeat(cid));
            for(Long uid:userIds) {
                String key=ticket(cid,uid);var type=original.type(key);
                if(type!=org.springframework.data.redis.connection.DataType.NONE)redis.put(key,Map.of(
                        "type",String.valueOf(type),"value",type==org.springframework.data.redis.connection.DataType.STRING
                                ?Objects.toString(original.opsForValue().get(key)):"invalid admission type",
                        "pttl",original.getExpire(key,TimeUnit.MILLISECONDS)));
            }
        }
        for(var attempt:attemptRepository.findAll()) {
            redis.put("booking:attempt:"+attempt.getId(),original.opsForHash().entries("booking:attempt:"+attempt.getId()));
            String key="booking:active:concert:"+attempt.getConcertId()+":user:"+attempt.getUserId();
            String value=original.opsForValue().get(key);if(value!=null)redis.put(key,value);
        }
        state.put("redis",redis);
        Path dir=resultRoot().resolve("evidence");Files.createDirectories(dir);
        Files.writeString(dir.resolve(info.getTestMethod().orElseThrow().getName()+"-"+UUID.randomUUID()+".json"),json.writeValueAsString(state));
    }
}
