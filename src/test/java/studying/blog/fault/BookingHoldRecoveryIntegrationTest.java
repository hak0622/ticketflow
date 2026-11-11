package studying.blog.fault;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import studying.blog.support.IntegrationTestSupport;
import studying.blog.domain.*;
import studying.blog.service.*;
import studying.blog.scheduler.BookingAttemptRecoveryWorker;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class BookingHoldRecoveryIntegrationTest extends IntegrationTestSupport {
    @Autowired BookingService service;
    @Autowired BookingAttemptTransactions transactions;
    @Autowired BookingSeatStore seats;
    @Autowired BookingSeatReleaseService release;
    @Autowired BookingAttemptRecoveryWorker worker;
    Concert concert;
    String id;
    @BeforeEach void fixture() {
        try { jdbcTemplate.execute("ALTER TABLE booking DROP CHECK reject_booking"); } catch (org.springframework.dao.DataAccessException ignored) { }
        concert = savedConcert("hold",10,0);
        queueService.initSeatCount(concert.getId(),10);
        queueService.restoreAdmitted(concert.getId(),1L,600);
        id=UUID.randomUUID().toString();
    }
    void due() { jdbcTemplate.update("update booking_attempt set next_retry_at=? where id=?", LocalDateTime.now().minusSeconds(1),id); }
    String key() { return "booking:attempt:"+id; }
    void held() { transactions.create(id,concert.getId(),1L); seats.reserve(attemptRepository.findById(id).orElseThrow()); }
    void booked() {
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(attemptRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(9);
    }
    @AfterEach void drop() { try { jdbcTemplate.execute("ALTER TABLE booking DROP CHECK reject_booking"); } catch (org.springframework.dao.DataAccessException ignored) { } }
    @Test void realInsertFailureKeepsHoldThenWorkerCreatesBooking() {
        jdbcTemplate.execute("ALTER TABLE booking ADD CONSTRAINT reject_booking CHECK (user_id <> 1)");
        assertThat(service.book(concert.getId(),1L,id).getStatus()).isEqualTo("BOOKING_PROCESSING");
        assertThat(bookingRepository.count()).isZero();
        assertThat(redisTemplate.opsForHash().get(key(),"state")).isEqualTo("HELD");
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(9);
        jdbcTemplate.execute("ALTER TABLE booking DROP CHECK reject_booking");
        due(); worker.recoverIncompleteRequests(); booked();
    }
    @Test void consumedAdmissionAndSoldOutDoNotPreventRecovery() {
        held();
        assertThat(queueService.isAdmitted(concert.getId(),1L)).isFalse();
        jdbcTemplate.update("update concert set status='SOLD_OUT' where id=?",concert.getId());
        due(); worker.recoverIncompleteRequests(); booked();
    }
    @Test void concurrentRequestAndWorkersOnlyCreateOnceAndNeverExtendLease() throws Exception {
        held(); due(); Object deadline=redisTemplate.opsForHash().get(key(),"holdExpiresAt");
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try {
            List<Callable<Object>> tasks=new ArrayList<>();
            for(int i=0;i<6;i++) tasks.add(() -> { worker.recoverIncompleteRequests(); return service.book(concert.getId(),1L,id); });
            for(var f:pool.invokeAll(tasks)) f.get();
        } finally {pool.shutdownNow();}
        booked(); assertThat(redisTemplate.opsForHash().get(key(),"holdExpiresAt")).isEqualTo(deadline);
    }
    @Test void expiredHoldReturnsOnceAndCannotReacquire() {
        held(); redisTemplate.opsForHash().put(key(),"holdExpiresAt","1"); due();
        worker.recoverIncompleteRequests(); worker.recoverIncompleteRequests();
        assertThat(bookingRepository.count()).isZero();
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(10);
        assertThat(service.book(concert.getId(),1L,id).getStatus()).isEqualTo("BOOKING_FAILED");
        assertThat(redisTemplate.opsForHash().get(key(),"releaseOutcome")).isEqualTo("RETURNED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"holdExpiresAt", "holdVersion"})
    void corruptDeadlineDoesNotReturnOrCreate(String missingField) {
        held(); redisTemplate.opsForHash().delete(key(),missingField); due(); worker.recoverIncompleteRequests();
        assertThat(bookingRepository.count()).isZero(); assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(9);
        assertThat(attemptRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.REQUESTED);
        assertThat(attemptRepository.findById(id).orElseThrow().getRetryCount()).isPositive();
        assertThat(attemptRepository.findById(id).orElseThrow().getNextRetryAt()).isAfter(LocalDateTime.now());
    }
}
