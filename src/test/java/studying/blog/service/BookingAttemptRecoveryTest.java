package studying.blog.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import studying.blog.domain.*;
import studying.blog.repository.*;
import studying.blog.scheduler.BookingAttemptRecoveryWorker;
import studying.blog.scheduler.PaymentCompensationOutboxProcessor;
import studying.blog.scheduler.BookingExpiryScheduler;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class BookingAttemptRecoveryTest {
    @Autowired BookingService service;
    @MockitoSpyBean BookingAttemptTransactions transactions;
    @MockitoSpyBean BookingSeatStore seats;
    @MockitoSpyBean BookingRepository bookings;
    @MockitoSpyBean BookingAttemptRepository attempts;
    @MockitoSpyBean ConcertRepository concerts;
    @Autowired PaymentRepository payments;
    @Autowired PaymentCompensationOutboxRepository outboxes;
    @MockitoSpyBean QueueService queue;
    @Autowired BookingSeatReleaseService release;
    @Autowired BookingAttemptRecoveryWorker worker;
    @Autowired BookingExpiryScheduler expiry;
    @Autowired PaymentCompensationOutboxProcessor outboxProcessor;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    Concert concert;

    @BeforeEach void setup() {
        reset(transactions, seats, bookings, attempts, concerts, queue);
        outboxes.deleteAll(); payments.deleteAll(); attempts.deleteAll(); bookings.deleteAll(); concerts.deleteAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        concert = concerts.saveAndFlush(Concert.builder().title("정합성 검증").totalSeats(10).bookedCount(0)
                .status(ConcertStatus.OPEN).eventAt(LocalDateTime.now().plusDays(1)).build());
        queue.initSeatCount(concert.getId(), 10);
        queue.restoreAdmitted(concert.getId(), 1L, 600);
    }
    String id() { return UUID.randomUUID().toString(); }
    BookingAttempt a(String id) { return attempts.findById(id).orElseThrow(); }
    void stale(String id) { jdbc.update("update booking_attempt set next_retry_at = ? where id = ?", LocalDateTime.now().minusSeconds(1), id); }
    void remaining(long n) { assertThat(queue.getRemainingSeat(concert.getId())).isEqualTo(n); }
    Booking booking() { return bookings.findByConcertIdAndUserId(concert.getId(), 1L).orElseThrow(); }

    @Test void successAndReplayOnlyDecrementOnce() {
        String id = id();
        assertThat(service.book(concert.getId(), 1L, id).getStatus()).isEqualTo("BOOKED");
        assertThat(service.book(concert.getId(), 1L, id).getStatus()).isEqualTo("BOOKED");
        remaining(9); assertThat(bookings.count()).isEqualTo(1);
        assertThat(a(id).getBookingId()).isEqualTo(booking().getId());
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
        assertThat(concerts.findById(concert.getId()).orElseThrow().getBookedCount()).isZero();
    }
    @Test void intentIsCommittedBeforeRedisAndBookingCommitFailureRetainsHold() {
        String id = id();
        doAnswer(call -> {
            // Independent transaction can see REQUESTED before Redis execution.
            var independent = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            BookingAttemptStatus visible = independent.execute(s -> a(id).getStatus());
            assertThat(visible).isEqualTo(BookingAttemptStatus.REQUESTED);
            Object result = call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("commit failed"); }
            });
            return result;
        }).when(seats).reserve(any());
        assertThat(service.book(concert.getId(), 1L, id).getStatus()).isEqualTo("BOOKING_PROCESSING");
        remaining(9); assertThat(bookings.count()).isZero();
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.REQUESTED);
        reset(seats); stale(id); worker.recoverIncompleteRequests();
        remaining(9); assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
    }
    @Test void reserveResponseLostRetainsHoldThenRecoversWithoutDecrement() {
        String id = id();
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("reserve response lost"); }).when(seats).reserve(any());
        assertThat(service.book(concert.getId(), 1L, id).getStatus()).isEqualTo("BOOKING_PROCESSING");
        remaining(9); reset(seats); stale(id); release.recover(id);
        assertThat(service.book(concert.getId(), 1L, id).getStatus()).isEqualTo("BOOKED");
        remaining(9);
    }
    @Test void actualCommitSucceededButResponseLostMustNeverRelease() {
        String id = id();
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("commit response lost"); })
                .when(transactions).execute(id, concert.getId(), 1L);
        assertThat(service.book(concert.getId(), 1L, id).getStatus()).isEqualTo("BOOKED");
        remaining(9); verify(seats, never()).release(any());
    }
    @Test void unresolvedDatabaseOutcomeDoesNotReleaseThenWorkerRecovers() {
        String id = id();
        doThrow(new IllegalStateException("DB down")).when(bookings).saveAndFlush(any());
        doThrow(new IllegalStateException("primary unavailable")).when(transactions).prepareFailed(eq(id), anyLong(), anyLong(), any());
        assertThat(service.book(concert.getId(), 1L, id).getStatus()).isEqualTo("BOOKING_PROCESSING");
        remaining(9); verify(seats, never()).release(any());
        reset(transactions, bookings); stale(id); worker.recoverIncompleteRequests();
        remaining(9); assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
    }
    @Test void abandonedRequestRecoveredAndLateReserveIsRejected() {
        String id = id(); transactions.create(id, concert.getId(), 1L);
        seats.reserve(a(id)); remaining(9);
        redis.opsForHash().put("booking:attempt:" + id, "holdExpiresAt", "1");
        stale(id); worker.recoverIncompleteRequests(); remaining(10);
        assertThat(seats.reserve(a(id))).isEqualTo(-4);
        assertThat(service.book(concert.getId(), 1L, id).getStatus()).isEqualTo("BOOKING_FAILED");
        remaining(10);
    }
    @Test void requestNeverReachedRedisAlsoGetsTombstone() {
        String id = id(); transactions.create(id, concert.getId(), 1L);
        stale(id); worker.recoverIncompleteRequests();
        assertThat(seats.reserve(a(id))).isEqualTo(-4); remaining(10);
    }
    @Test void releaseResponseLostRetriedWithoutDoubleIncrement() {
        String id = id(); service.book(concert.getId(), 1L, id);
        transactions.cancel(booking().getId(), null);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("release response lost"); }).when(seats).release(any());
        release.releaseSafely(id);
        remaining(10); assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASE_PENDING);
        reset(seats); stale(id); worker.recoverIncompleteRequests(); remaining(10);
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
    }
    @Test void workerWaitsForTheOriginalRequestRowLock() throws Exception {
        String id = id();
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); })
                .when(seats).reserve(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> original = pool.submit(() -> service.book(concert.getId(), 1L, id));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> recovery = pool.submit(() -> release.recover(id));
            assertThatThrownBy(() -> recovery.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            resume.countDown(); original.get(5, TimeUnit.SECONDS); recovery.get(5, TimeUnit.SECONDS);
            remaining(9); assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.BOOKED);
            verify(seats, never()).release(any());
        } finally { resume.countDown(); pool.shutdownNow(); }
    }
    @Test void concurrentSameIdOnlyCreatesOneBooking() throws Exception {
        String id = id(); ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> service.book(concert.getId(), 1L, id).getStatus());
            for (Future<String> f : pool.invokeAll(tasks)) assertThat(f.get()).isEqualTo("BOOKED");
            remaining(9); assertThat(bookings.count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredBookingAndTwoDifferentOutboxesUseTheSameReturn() throws Exception {
        String id = id(); service.book(concert.getId(), 1L, id);
        Long bookingId = booking().getId();
        jdbc.update("update booking set created_at = ? where id = ?", LocalDateTime.now().minusMinutes(31), bookingId);
        expiry.expireStaleBookings(); remaining(10);
        for (int i = 0; i < 2; i++) {
            var outbox = outboxes.saveAndFlush(PaymentCompensationOutbox.create("{\"bookingId\":" + bookingId + ",\"concertId\":" + concert.getId() + "}"));
            outboxProcessor.process(outbox.getId());
            assertThat(outboxes.findById(outbox.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        }
        release.recover(id); remaining(10);
    }
    @Test void cancelledButUnreturnedBookingIsStillReturned() {
        String id = id(); service.book(concert.getId(), 1L, id);
        transactions.cancel(booking().getId(), null);
        assertThat(booking().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        remaining(9); worker.recoverIncompleteRequests(); remaining(10);
    }
    @Test void confirmedBookingCannotBeCancelledOrReturned() {
        String id = id(); service.book(concert.getId(), 1L, id);
        Booking b = booking(); b.confirm(); bookings.saveAndFlush(b);
        release.cancelAndRelease(b.getId(), null); release.recover(id);
        remaining(9); assertThat(booking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }
    @Test void missingCounterNeverGetsRecreatedByReturn() {
        String id = id(); service.book(concert.getId(), 1L, id);
        transactions.cancel(booking().getId(), null); redis.delete("seats:concert:" + concert.getId());
        worker.recoverIncompleteRequests();
        assertThat(queue.getRemainingSeat(concert.getId())).isNull();
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASE_PENDING);
    }
    @Test void admissionUsesOriginalAbsoluteExpiry() {
        String id = id(); transactions.create(id, concert.getId(), 1L);
        Instant until = Instant.now().plusSeconds(5);
        redis.expireAt("admitted:concert:" + concert.getId() + ":user:1", until);
        seats.reserve(a(id));
        redis.opsForHash().put("booking:attempt:" + id, "holdExpiresAt", "1");
        transactions.prepareFailed(id, concert.getId(), 1L, "failed");
        transactions.release(id);
        Long ttl = redis.getExpire("admitted:concert:" + concert.getId() + ":user:1", TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(5000);
        remaining(10);
    }
    @Test void wrongKeyTypeDoesNotPartiallyDecrement() {
        String id = id(); transactions.create(id, concert.getId(), 1L);
        String key = "booking:active:concert:" + concert.getId() + ":user:1";
        redis.opsForList().leftPush(key, "bad");
        assertThatThrownBy(() -> seats.reserve(a(id))).isInstanceOf(RuntimeException.class);
        remaining(10); assertThat(queue.isAdmitted(concert.getId(), 1L)).isTrue();
        assertThat(redis.hasKey("booking:attempt:" + id)).isFalse();
    }
    @Test void releaseCommitFailureIsRetriedWithoutDoubleIncrement() {
        String id = id(); service.book(concert.getId(), 1L, id);
        transactions.cancel(booking().getId(), null);
        doAnswer(call -> {
            call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("release commit failed"); }
            });
            return null;
        }).when(seats).release(any());
        release.releaseSafely(id);
        remaining(10); assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASE_PENDING);
        reset(seats); stale(id); worker.recoverIncompleteRequests();
        remaining(10); assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
    }
    @Test void concurrentReturnWorkersOnlyIncrementOnce() throws Exception {
        String id = id(); service.book(concert.getId(), 1L, id);
        transactions.cancel(booking().getId(), null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> { release.recover(id); return null; });
            for (Future<Void> f : pool.invokeAll(tasks)) f.get();
            remaining(10); assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
        } finally { pool.shutdownNow(); }
    }
    @Test void differentRequestIdsForTheSameUserStillOnlyConsumeOneSeat() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> {
                try { service.book(concert.getId(), 1L, id()); } catch (IllegalStateException expectedConflict) { }
                return null;
            });
            for (Future<Void> f : pool.invokeAll(tasks)) f.get();
            remaining(9); assertThat(bookings.count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void requestIdCannotBeReusedForAnotherOwner() {
        String id = id(); service.book(concert.getId(), 1L, id);
        assertThatThrownBy(() -> service.book(concert.getId(), 2L, id)).isInstanceOf(IllegalArgumentException.class);
        remaining(9);
    }
    void soldOutRequest(String id) {
        assertThatThrownBy(() -> service.book(concert.getId(), 1L, id)).hasMessage("정원이 마감되었습니다.");
    }
    void soldOutState(String id, long count) {
        remaining(count);
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.REJECTED);
        assertThat(a(id).getResultStatus()).isEqualTo("SOLD_OUT");
        assertThat(a(id).getBookingId()).isNull();
        assertThat(bookings.count()).isZero();
        assertThat(redis.opsForHash().entries("booking:attempt:" + id))
                .containsEntry("state", "RELEASED").containsEntry("result", "SOLD_OUT");
    }
    @Test void soldOutCommitsWithoutCompensationAndReplayNeverAcquiresNewStock() {
        queue.initSeatCount(concert.getId(), 0);
        String id = id();
        clearInvocations(attempts, bookings, concerts);
        soldOutRequest(id);
        verify(attempts, never()).findById(anyString());
        verify(transactions, never()).prepareFailed(anyString(), anyLong(), anyLong(), any());
        verify(transactions, never()).release(anyString());
        verify(seats, never()).release(any());
        verify(seats, never()).isSoldOut(any());
        verify(bookings, never()).saveAndFlush(any());
        assertThat(queue.isAdmitted(concert.getId(), 1L)).isTrue();
        soldOutState(id, 0);
        queue.initSeatCount(concert.getId(), 5);
        clearInvocations(seats, bookings, concerts);
        soldOutRequest(id);
        verifyNoInteractions(seats, bookings, concerts);
        assertThat(seats.reserve(a(id))).isEqualTo(-1);
        seats.release(a(id)); seats.release(a(id));
        soldOutState(id, 5);
    }
    @Test void soldOutRedisResponseLostIsResolvedFromTerminalMarker() {
        queue.initSeatCount(concert.getId(), 0);
        String id = id();
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("response lost"); }).when(seats).reserve(any());
        soldOutRequest(id);
        soldOutState(id, 0);
        verify(seats, never()).release(any());
    }
    @Test void soldOutCommitAckLostReusesCommittedOutcome() {
        queue.initSeatCount(concert.getId(), 0);
        String id = id();
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("commit ack lost"); })
                .when(transactions).execute(id, concert.getId(), 1L);
        soldOutRequest(id);
        soldOutState(id, 0);
        verify(seats, never()).release(any());
        verify(seats, never()).isSoldOut(any());
    }
    @Test void pendingSoldOutRecoveredWithoutReturnAndWithoutRestoringExpiredAdmission() {
        queue.initSeatCount(concert.getId(), 0);
        String id = id(); transactions.create(id, concert.getId(), 1L);
        assertThat(seats.reserve(a(id))).isEqualTo(-1);
        jdbc.update("update booking_attempt set status = 'RELEASE_PENDING' where id = ?", id);
        redis.delete("admitted:concert:" + concert.getId() + ":user:1");
        queue.initSeatCount(concert.getId(), 5);
        stale(id); worker.recoverIncompleteRequests(); worker.recoverIncompleteRequests();
        soldOutState(id, 5);
        assertThat(queue.isAdmitted(concert.getId(), 1L)).isFalse();
        verify(transactions, never()).release(anyString());
        verify(seats, never()).release(any());
        soldOutRequest(id);
    }
    @Test void recoveryOutcomeReadFailureLeavesIntentUntouchedUntilRetry() {
        queue.initSeatCount(concert.getId(), 0);
        String id = id(); transactions.create(id, concert.getId(), 1L); seats.reserve(a(id));
        doThrow(new IllegalStateException("Redis read unavailable")).when(seats).isSoldOut(any());
        stale(id); worker.recoverIncompleteRequests();
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.REQUESTED);
        remaining(0); verify(seats, never()).release(any());
        reset(seats); stale(id); worker.recoverIncompleteRequests(); soldOutState(id, 0);
    }
    @Test void invalidOutcomeOrOwnerIsNotTreatedAsSoldOutOrReleased() {
        String id = id(); transactions.create(id, concert.getId(), 1L);
        for (Map<String, String> fields : List.of(
                Map.of("owner", "other:owner", "state", "RELEASED", "result", "SOLD_OUT"),
                Map.of("owner", concert.getId() + ":1", "state", "HELD", "result", "SOLD_OUT"))) {
            redis.opsForHash().putAll("booking:attempt:" + id, fields);
            stale(id); worker.recoverIncompleteRequests();
            assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.REQUESTED);
            remaining(10); verify(seats, never()).release(any());
        }
    }
    @Test void ownershipFailureSkipsCompensationAndSoldOutConcurrentReplayIsStable() throws Exception {
        queue.initSeatCount(concert.getId(), 0);
        String id = id();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) calls.add(() -> { soldOutRequest(id); return null; });
            for (Future<Void> f : pool.invokeAll(calls)) f.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThatThrownBy(() -> service.book(concert.getId(), 2L, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.book(concert.getId() + 99, 1L, id)).isInstanceOf(IllegalArgumentException.class);
        verify(transactions, never()).prepareFailed(anyString(), anyLong(), anyLong(), any());
        soldOutState(id, 0);
    }
    @Test void successDoesNotReadOutcomeAndAlreadyBookedUsesTitleProjection() {
        String id = id(); service.book(concert.getId(), 1L, id);
        verify(seats, never()).isSoldOut(any());
        clearInvocations(bookings, concerts, seats);
        assertThat(service.book(concert.getId(), 1L, id()).getStatus()).isEqualTo("ALREADY_BOOKED");
        verify(bookings).findBookedConcertTitle(concert.getId(), 1L);
        verify(bookings, never()).findByConcertIdAndUserId(anyLong(), anyLong());
        verifyNoInteractions(concerts);
        verify(seats).holdRemaining(any());
        verify(seats, never()).reserve(any());
    }

    @Test void workerWaitsForSoldOutTransactionThenSkipsReturn() throws Exception {
        queue.initSeatCount(concert.getId(), 0);
        String id = id();
        CountDownLatch reserved = new CountDownLatch(1), resume = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            reserved.countDown();
            assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(seats).reserve(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> original = pool.submit(() -> soldOutRequest(id));
            assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> recovery = pool.submit(() -> release.recover(id));
            assertThatThrownBy(() -> recovery.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            resume.countDown();
            original.get(5, TimeUnit.SECONDS); recovery.get(5, TimeUnit.SECONDS);
            soldOutState(id, 0);
            verify(seats, never()).release(any());
            verify(transactions, never()).release(anyString());
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test void neverHeldReleaseSkipsAllInventoryPostProcessing() {
        String id = id(); transactions.create(id, concert.getId(), 1L);
        transactions.prepareFailed(id, concert.getId(), 1L, "not executed");
        clearInvocations(queue, concerts);
        transactions.release(id);
        verify(queue, never()).getRemainingSeat(anyLong());
        verify(queue, never()).deleteConcertStatus(anyLong());
        verify(concerts, never()).reopenIfSoldOutById(anyLong());
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
        assertThat(seats.release(a(id))).isEqualTo(BookingSeatStore.ReleaseResult.NOT_HELD);
        remaining(10);
    }
    @Test void resoldReturnedTicketDoesNotReopenSoldOutConcertOnReplay() {
        jdbc.update("update concert set total_seats=1 where id=?", concert.getId());
        queue.initSeatCount(concert.getId(), 1);
        String first = id(); service.book(concert.getId(), 1L, first);
        transactions.cancel(booking().getId(), null);
        assertThat(seats.release(a(first))).isEqualTo(BookingSeatStore.ReleaseResult.RETURNED);
        queue.restoreAdmitted(concert.getId(), 2L, 600);
        assertThat(service.book(concert.getId(), 2L, id()).getStatus()).isEqualTo("BOOKED");
        clearInvocations(concerts);
        release.recover(first);
        remaining(0);
        verify(concerts, never()).reopenIfSoldOutById(anyLong());
        assertThat(concerts.findById(concert.getId()).orElseThrow().getStatus()).isEqualTo(ConcertStatus.SOLD_OUT);
        assertThat(a(first).getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
    }
    @Test void returnedTicketReplayNeverReopensClosedConcert() {
        String id = id(); service.book(concert.getId(), 1L, id);
        transactions.cancel(booking().getId(), null);
        seats.release(a(id));
        jdbc.update("update concert set status='CLOSED' where id=?", concert.getId());
        release.recover(id);
        remaining(10);
        assertThat(concerts.findById(concert.getId()).orElseThrow().getStatus()).isEqualTo(ConcertStatus.CLOSED);
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
    }
    @Test void legacyReleasedMarkerStillRetriesPostProcessingAndReopensWhenSeatsRemain() {
        String id = id(); service.book(concert.getId(), 1L, id);
        transactions.cancel(booking().getId(), null);
        seats.release(a(id));
        redis.opsForHash().delete("booking:attempt:" + id, "releaseOutcome");
        jdbc.update("update concert set status='SOLD_OUT' where id=?", concert.getId());
        assertThat(seats.release(a(id))).isEqualTo(BookingSeatStore.ReleaseResult.LEGACY_UNKNOWN);
        release.recover(id);
        remaining(10);
        assertThat(concerts.findById(concert.getId()).orElseThrow().getStatus()).isEqualTo(ConcertStatus.OPEN);
    }
    @Test void postProcessingFailureRetriesWithoutDoubleReturnAndRestoresSoldOutStatus() {
        String id = id(); service.book(concert.getId(), 1L, id);
        transactions.cancel(booking().getId(), null);
        jdbc.update("update concert set status='SOLD_OUT' where id=?", concert.getId());
        doThrow(new IllegalStateException("cache unavailable")).when(queue).deleteConcertStatus(anyLong());
        release.releaseSafely(id);
        remaining(10);
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASE_PENDING);
        assertThat(concerts.findById(concert.getId()).orElseThrow().getStatus()).isEqualTo(ConcertStatus.SOLD_OUT);
        reset(queue);
        stale(id); worker.recoverIncompleteRequests();
        remaining(10);
        assertThat(concerts.findById(concert.getId()).orElseThrow().getStatus()).isEqualTo(ConcertStatus.OPEN);
        assertThat(a(id).getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
    }
    @Autowired javax.sql.DataSource dataSource;
    @Test void connectionIsReturnedAtTransactionBoundariesButHeldDuringRedis() throws Exception {
        var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean();
        assertThat(pool.getActiveConnections()).isZero();
        String id = id(); transactions.create(id, concert.getId(), 1L);
        assertThat(pool.getActiveConnections()).isZero();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(pool.getActiveConnections()).isGreaterThanOrEqualTo(1);
            return call.callRealMethod();
        }).when(seats).reserve(any());
        transactions.execute(id, concert.getId(), 1L);
        assertThat(pool.getActiveConnections()).isZero();
    }

}
