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

@org.springframework.boot.test.context.SpringBootTest(properties="spring.task.scheduling.enabled=true")
class BookingHoldLeaseClockIntegrationTest extends IntegrationTestSupport {
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
    @Test void actualFiveMinuteLeaseExpiresWithoutBooking() throws Exception {
        jdbcTemplate.execute("ALTER TABLE booking ADD CONSTRAINT reject_booking CHECK (user_id <> 1)");
        assertThat(service.book(concert.getId(),1L,id).getStatus()).isEqualTo("BOOKING_PROCESSING");
        long started=System.nanoTime();
        while ((System.nanoTime()-started)/1_000_000 < 301000) Thread.sleep(1000);
        // Scheduler runs normally throughout; no fixture deadline updates or manual recovery.
        long end=System.nanoTime()+java.time.Duration.ofSeconds(70).toNanos();
        while (attemptRepository.findById(id).orElseThrow().getStatus()!=BookingAttemptStatus.RELEASED && System.nanoTime()<end) Thread.sleep(1000);
        assertThat(attemptRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingAttemptStatus.RELEASED);
        assertThat(bookingRepository.count()).isZero();
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(10);
    }

}
