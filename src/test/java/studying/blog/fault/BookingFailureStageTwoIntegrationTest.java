package studying.blog.fault;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import studying.blog.domain.*;
import studying.blog.repository.*;
import studying.blog.scheduler.*;
import studying.blog.service.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.data.redis.timeout=1s",
        "spring.datasource.hikari.maximum-pool-size=12", "spring.jpa.show-sql=false",
        "logging.level.p6spy=OFF", "logging.level.org.hibernate.SQL=OFF"})
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers
@Import(BookingFailureStageTwoIntegrationTest.GateConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BookingFailureStageTwoIntegrationTest {
    static class GateConfiguration {
        @Bean BookingBoundaryGate bookingBoundaryGate() { return new BookingBoundaryGate(); }
    }
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withCommand("--log-bin-trust-function-creators=1");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    static ResponseLossProxy mysqlWire;
    static ResponseLossProxy redisWire;
    static String jdbcUrl;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        mysqlWire = new ResponseLossProxy(ResponseLossProxy.Protocol.MYSQL, MYSQL.getHost(), MYSQL.getMappedPort(3306));
        redisWire = new ResponseLossProxy(ResponseLossProxy.Protocol.REDIS, REDIS.getHost(), REDIS.getMappedPort(6379));
        jdbcUrl = "jdbc:mysql://127.0.0.1:" + mysqlWire.port() + "/" + MYSQL.getDatabaseName()
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=2000&socketTimeout=10000";
        r.add("spring.datasource.url", () -> jdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        r.add("spring.data.redis.host", () -> "127.0.0.1");
        r.add("spring.data.redis.port", redisWire::port);
    }

    @Autowired BookingService bookingService;
    @Autowired BookingAttemptTransactions transactions;
    @Autowired BookingSeatReleaseService release;
    @Autowired BookingSeatStore seatStore;
    @Autowired BookingAttemptRecoveryWorker worker;
    @Autowired BookingExpiryScheduler expiry;
    @Autowired PaymentCompensationOutboxProcessor outboxProcessor;
    @Autowired BookingAttemptRepository attempts;
    @Autowired BookingRepository bookings;
    @Autowired ConcertRepository concerts;
    @Autowired PaymentRepository payments;
    @Autowired PaymentCompensationOutboxRepository outboxes;
    @Autowired QueueService queue;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingBoundaryGate gate;
    Long concertId;

    @BeforeEach void setup() {
        mysqlWire.disarm(); redisWire.disarm(); gate.resume();
        jdbc.execute("DROP TRIGGER IF EXISTS fault_booking_insert");
        jdbc.execute("DROP TRIGGER IF EXISTS fault_rejected_update");
        outboxes.deleteAll(); payments.deleteAll(); attempts.deleteAll(); bookings.deleteAll(); concerts.deleteAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        Concert c = concerts.saveAndFlush(Concert.builder().title("2단계 장애 검증").totalSeats(10).bookedCount(0)
                .status(ConcertStatus.OPEN).eventAt(LocalDateTime.now().plusDays(1)).build());
        concertId = c.getId();
        queue.initSeatCount(concertId, 10);
        queue.restoreAdmitted(concertId, 1L, 600);
    }
    @AfterEach void cleanup() {
        gate.resume(); mysqlWire.disarm(); redisWire.disarm();
        ((LettuceConnectionFactory) redis.getConnectionFactory()).resetConnection();
        jdbc.execute("DROP TRIGGER IF EXISTS fault_booking_insert");
        jdbc.execute("DROP TRIGGER IF EXISTS fault_rejected_update");
    }
    String id() { return UUID.randomUUID().toString(); }
    BookingAttempt attempt(String id) { return attempts.findById(id).orElseThrow(); }
    void due(String id) { jdbc.update("UPDATE booking_attempt SET next_retry_at = ? WHERE id = ?", LocalDateTime.now().minusSeconds(1), id); }
    long bookingId() { return jdbc.queryForObject("SELECT id FROM booking WHERE concert_id = ? AND user_id = 1", Long.class, concertId); }
    String lua(String path) throws Exception {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
    /** Reads all three stores afresh; asserts both values and the attempt-to-booking link. */
    void assertState(String id, long remaining, BookingStatus bookingStatus, BookingAttemptStatus status) {
        assertThat(queue.getRemainingSeat(concertId)).as("Redis remaining").isEqualTo(remaining);
        assertThat(jdbc.queryForObject("SELECT status FROM booking_attempt WHERE id = ?", String.class, id))
                .as("MySQL attempt status").isEqualTo(status.name());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, status FROM booking WHERE concert_id = ? AND user_id = 1", concertId);
        if (bookingStatus == null) {
            assertThat(rows).as("No committed booking").isEmpty();
            assertThat(attempt(id).getBookingId()).isNull();
        } else {
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("status").toString()).isEqualTo(bookingStatus.name());
            assertThat(attempt(id).getBookingId()).isEqualTo(((Number) rows.get(0).get("id")).longValue());
        }
        if (status == BookingAttemptStatus.RELEASED) {
            assertThat(redis.opsForHash().get("booking:attempt:" + id, "state")).isEqualTo("RELEASED");
        }
        if (status == BookingAttemptStatus.BOOKED) {
            assertThat(redis.opsForHash().get("booking:attempt:" + id, "state")).isEqualTo("HELD");
        }
        System.out.printf("[STAGE2_STATE] request=%s remaining=%d booking=%s attempt=%s%n",
                id, remaining, bookingStatus == null ? "NONE" : bookingStatus, status);
        if (status == BookingAttemptStatus.BOOKED || status == BookingAttemptStatus.RELEASED) {
            Long active = jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE concert_id = ? AND status IN ('PENDING_PAYMENT','CONFIRMED')", Long.class, concertId);
            assertThat(remaining + active).as("Quiescent quantity conservation").isEqualTo(10);
        }
    }
    void recover(String id) {
        ((LettuceConnectionFactory) redis.getConnectionFactory()).resetConnection();
        due(id); worker.recoverIncompleteRequests();
    }

    void assertSoldOut(String id, long count) {
        assertState(id, count, null, BookingAttemptStatus.REJECTED);
        assertThat(attempt(id).getResultStatus()).isEqualTo("SOLD_OUT");
        assertThat(redis.opsForHash().entries("booking:attempt:" + id))
                .containsEntry("state", "RELEASED").containsEntry("result", "SOLD_OUT");
    }
    void expectSoldOut(String id) {
        assertThatThrownBy(() -> bookingService.book(concertId, 1L, id)).hasMessage("정원이 마감되었습니다.");
    }

    @Test void soldOutMarkerSurvivesRealMysqlRejectedUpdateFailureAndWorkerPreservesInventory() {
        String id = id(); queue.initSeatCount(concertId, 0);
        jdbc.execute("CREATE TRIGGER fault_rejected_update BEFORE UPDATE ON booking_attempt FOR EACH ROW "
                + "BEGIN IF NEW.status = 'REJECTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reject soldout commit'; END IF; END");
        assertThat(bookingService.book(concertId, 1L, id).getStatus()).isEqualTo("BOOKING_PROCESSING");
        assertState(id, 0, null, BookingAttemptStatus.REQUESTED);
        assertThat(redis.opsForHash().get("booking:attempt:" + id, "result")).isEqualTo("SOLD_OUT");
        assertThat(queue.isAdmitted(concertId, 1L)).isTrue();
        jdbc.execute("DROP TRIGGER fault_rejected_update");
        redis.delete("admitted:concert:" + concertId + ":user:1");
        queue.initSeatCount(concertId, 5);
        recover(id); recover(id);
        assertSoldOut(id, 5);
        assertThat(queue.isAdmitted(concertId, 1L)).isFalse();
        expectSoldOut(id);
        assertThat(seatStore.reserve(attempt(id))).isEqualTo(-1);
        seatStore.release(attempt(id)); seatStore.release(attempt(id));
        assertSoldOut(id, 5);
        assertThat(queue.isAdmitted(concertId, 1L)).isFalse();
    }
    @Test void realSoldOutCommitAckLostStillReturnsSavedSoldOut() throws Exception {
        String id = id(); queue.initSeatCount(concertId, 0);
        int before = mysqlWire.dropCount();
        gate.arm(id, mysqlWire::loseNextCommitReply, false);
        expectSoldOut(id);
        assertThat(mysqlWire.awaitDrop(Duration.ofSeconds(5))).isTrue();
        assertThat(mysqlWire.dropCount()).isEqualTo(before + 1);
        assertSoldOut(id, 0);
        expectSoldOut(id);
        assertThat(queue.isAdmitted(concertId, 1L)).isTrue();
    }
    @Test void realSoldOutRedisReplyLostIsRecoveredAsSoldOutRatherThanReturned() throws Exception {
        String id = id(); queue.initSeatCount(concertId, 0);
        int before = redisWire.dropCount();
        redisWire.blackholeNextLuaReply(lua("redis/reserve-booking.lua"));
        // The connection remains blackholed, so even outcome inspection must defer recovery.
        assertThat(bookingService.book(concertId, 1L, id).getStatus()).isEqualTo("BOOKING_PROCESSING");
        assertThat(redisWire.awaitDrop(Duration.ofSeconds(5))).isTrue();
        assertThat(redisWire.dropCount()).isEqualTo(before + 1);
        recover(id);
        assertSoldOut(id, 0);
        assertThat(queue.isAdmitted(concertId, 1L)).isTrue();
        expectSoldOut(id);
    }

    @Test void redisDecrementThenRealMysqlInsertFailureReturnsExactlyOneSeat() {
        String id = id();
        jdbc.execute("CREATE TRIGGER fault_booking_insert BEFORE INSERT ON booking FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stage2 real insert failure'");
        gate.arm(id, () -> assertState(id, 9, null, BookingAttemptStatus.REQUESTED), false);
        assertThatThrownBy(() -> bookingService.book(concertId, 1L, id)).isInstanceOf(RuntimeException.class);
        assertState(id, 10, null, BookingAttemptStatus.RELEASED);
        assertThat(attempt(id).getLastError()).contains("stage2 real insert failure");
        recover(id); assertState(id, 10, null, BookingAttemptStatus.RELEASED);
    }

    @Test void mysqlCommitOkReplyLostStillKeepsCommittedBookingAndSeat() throws Exception {
        String id = id(); int before = mysqlWire.dropCount();
        gate.arm(id, mysqlWire::loseNextCommitReply, false);
        assertThat(bookingService.book(concertId, 1L, id).getStatus()).isEqualTo("BOOKED");
        assertThat(mysqlWire.awaitDrop(Duration.ofSeconds(5))).isTrue();
        assertThat(mysqlWire.dropCount()).isEqualTo(before + 1);
        assertState(id, 9, BookingStatus.PENDING_PAYMENT, BookingAttemptStatus.BOOKED);
        worker.recoverIncompleteRequests();
        assertThat(bookingService.book(concertId, 1L, id).getStatus()).isEqualTo("BOOKED");
        assertState(id, 9, BookingStatus.PENDING_PAYMENT, BookingAttemptStatus.BOOKED);
    }

    @Test void realRedisReserveReplyLostIsRecoveredWithoutDoubleReturn() throws Exception {
        String id = id(); int before = redisWire.dropCount();
        redisWire.blackholeNextLuaReply(lua("redis/reserve-booking.lua"));
        // Outcome inspection uses the same unavailable Redis connection: defer without release.
        assertThat(bookingService.book(concertId, 1L, id).getStatus()).isEqualTo("BOOKING_PROCESSING");
        assertThat(redisWire.awaitDrop(Duration.ofSeconds(5))).isTrue();
        assertThat(redisWire.dropCount()).isEqualTo(before + 1);
        recover(id);
        assertState(id, 10, null, BookingAttemptStatus.RELEASED);
        assertThat(bookingService.book(concertId, 1L, id).getStatus()).isEqualTo("BOOKING_FAILED");
        assertState(id, 10, null, BookingAttemptStatus.RELEASED);
    }

    @Test void redisReconnectReplayAfterLostReplyAlsoConsumesOnlyOneSeat() throws Exception {
        String id = id(); int before = redisWire.dropCount();
        redisWire.loseNextLuaReply(lua("redis/reserve-booking.lua"));
        assertThat(bookingService.book(concertId, 1L, id).getStatus()).isEqualTo("BOOKED");
        assertThat(redisWire.awaitDrop(Duration.ofSeconds(5))).isTrue();
        assertThat(redisWire.dropCount()).isEqualTo(before + 1);
        assertState(id, 9, BookingStatus.PENDING_PAYMENT, BookingAttemptStatus.BOOKED);
        assertThat(bookingService.book(concertId, 1L, id).getStatus()).isEqualTo("BOOKED");
        assertState(id, 9, BookingStatus.PENDING_PAYMENT, BookingAttemptStatus.BOOKED);
    }

    @Test void realRedisReturnReplyLostLeavesDurableWorkAndRetryDoesNotIncrementAgain() throws Exception {
        String id = id(); bookingService.book(concertId, 1L, id);
        transactions.cancel(bookingId(), null);
        int before = redisWire.dropCount();
        redisWire.blackholeNextLuaReply(lua("redis/release-booking.lua"));
        release.releaseSafely(id);
        assertThat(redisWire.awaitDrop(Duration.ofSeconds(5))).isTrue();
        assertThat(redisWire.dropCount()).isEqualTo(before + 1);
        ((LettuceConnectionFactory) redis.getConnectionFactory()).resetConnection();
        assertState(id, 10, BookingStatus.CANCELLED, BookingAttemptStatus.RELEASE_PENDING);
        recover(id);
        assertState(id, 10, BookingStatus.CANCELLED, BookingAttemptStatus.RELEASED);
        recover(id);
        assertState(id, 10, BookingStatus.CANCELLED, BookingAttemptStatus.RELEASED);
    }

    @Test void actualApplicationProcessKilledAfterReserveIsRecoveredByWorker() throws Exception {
        String id = id();
        Path log = Files.createTempFile("ticketflow-killed-booking-", ".log");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Objects.requireNonNull(System.getProperty("faultTest.classpath")), CrashBookingProcess.class.getName());
        Map<String, String> env = builder.environment();
        env.put("SPRING_PROFILES_ACTIVE", "test");
        env.put("APP_TEST_SUPPORT_ENABLED", "false");
        env.put("SPRING_JPA_SHOW_SQL", "false");
        env.put("LOGGING_LEVEL_P6SPY", "OFF");
        env.put("SPRING_DATASOURCE_URL", jdbcUrl);
        env.put("SPRING_DATASOURCE_USERNAME", MYSQL.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", MYSQL.getPassword());
        env.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME", "com.mysql.cj.jdbc.Driver");
        env.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "none");
        env.put("SPRING_DATA_REDIS_HOST", "127.0.0.1");
        env.put("SPRING_DATA_REDIS_PORT", String.valueOf(redisWire.port()));
        env.put("SPRING_TASK_SCHEDULING_ENABLED", "false");
        env.put("SERVER_PORT", "0");
        env.put("FAULT_REQUEST_ID", id); env.put("FAULT_CONCERT_ID", concertId.toString());
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            awaitCondition(Duration.ofSeconds(60), () -> {
                try { return Files.readString(log).contains("FAULT_PROCESS_AFTER_REDIS " + id); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }, () -> "Child did not reach boundary. alive=" + process.isAlive() + " log=" + log);
            assertState(id, 9, null, BookingAttemptStatus.REQUESTED);
            assertThat(process.isAlive()).as("Application is alive immediately before forced termination").isTrue();
            long killedPid = process.pid();
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("Killed application JVM terminated").isTrue();
            assertThat(process.exitValue()).isNotZero();
            System.out.printf("[STAGE2_KILL] pid=%d exit=%d request=%s log=%s%n", killedPid, process.exitValue(), id, log);
            recover(id);
            assertState(id, 10, null, BookingAttemptStatus.RELEASED);
            assertThat(seatStore.reserve(attempt(id))).isEqualTo(-4);
            assertState(id, 10, null, BookingAttemptStatus.RELEASED);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
    }

    @Test void originalRequestAndWorkerContendOnTheSameRealMysqlRow() throws Exception {
        String id = id(); transactions.create(id, concertId, 1L); due(id);
        gate.arm(id, null, true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> original = pool.submit(() -> bookingService.book(concertId, 1L, id));
            assertThat(gate.await(10)).isTrue();
            assertState(id, 9, null, BookingAttemptStatus.REQUESTED);
            Future<?> recovery = pool.submit(worker::recoverIncompleteRequests);
            assertThatThrownBy(() -> recovery.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            gate.resume(); original.get(10, TimeUnit.SECONDS); recovery.get(10, TimeUnit.SECONDS);
            assertState(id, 9, BookingStatus.PENDING_PAYMENT, BookingAttemptStatus.BOOKED);
        } finally { gate.resume(); pool.shutdownNow(); }
    }

    @Test void concurrentDuplicateReserveAndReturnAreBothIdempotent() throws Exception {
        String id = id();
        runTogether(12, () -> assertThat(bookingService.book(concertId, 1L, id).getStatus()).isEqualTo("BOOKED"));
        assertState(id, 9, BookingStatus.PENDING_PAYMENT, BookingAttemptStatus.BOOKED);
        transactions.cancel(bookingId(), null);
        runTogether(12, () -> release.recover(id));
        assertState(id, 10, BookingStatus.CANCELLED, BookingAttemptStatus.RELEASED);
    }

    @Test void expiryAndPaymentCompensationExecuteConcurrentlyAndReturnOnlyOnce() throws Exception {
        String id = id(); bookingService.book(concertId, 1L, id);
        long bookingId = bookingId();
        jdbc.update("UPDATE booking SET created_at = ? WHERE id = ?", LocalDateTime.now().minusMinutes(31), bookingId);
        var payment = Payment.create(bookings.findById(bookingId).orElseThrow(), 1L, concertId, 90000, "CARD", id());
        payment.fail("stage2 payment failure"); payments.saveAndFlush(payment);
        var outbox = outboxes.saveAndFlush(PaymentCompensationOutbox.create("{\"bookingId\":" + bookingId + ",\"concertId\":" + concertId + "}"));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> expired = pool.submit(() -> { ready.countDown(); await(start); expiry.expireStaleBookings(); });
            Future<?> compensated = pool.submit(() -> {
                ready.countDown(); await(start);
                try { outboxProcessor.process(outbox.getId()); } catch (Exception e) { throw new CompletionException(e); }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            expired.get(10, TimeUnit.SECONDS); compensated.get(10, TimeUnit.SECONDS);
            assertState(id, 10, BookingStatus.CANCELLED, BookingAttemptStatus.RELEASED);
            assertThat(outboxes.findById(outbox.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            expiry.expireStaleBookings(); outboxProcessor.process(outbox.getId()); worker.recoverIncompleteRequests();
            assertState(id, 10, BookingStatus.CANCELLED, BookingAttemptStatus.RELEASED);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    static void runTogether(int count, Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> { ready.countDown(); await(start); action.run(); }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    static void awaitCondition(Duration limit, BooleanSupplier condition, java.util.function.Supplier<String> message) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).withFailMessage(message).isTrue();
    }
}
