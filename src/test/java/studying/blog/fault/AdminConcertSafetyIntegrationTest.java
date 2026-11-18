package studying.blog.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import studying.blog.config.TokenProvider;
import studying.blog.domain.*;
import studying.blog.dto.ConcertAdminUpsertRequest;
import studying.blog.repository.UserRepository;
import studying.blog.service.BookingSeatStore;
import studying.blog.service.BookingService;
import studying.blog.service.ConcertService;
import studying.blog.service.QueueService;
import studying.blog.support.IntegrationTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@AutoConfigureMockMvc
class AdminConcertSafetyIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired TokenProvider tokens;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    @Autowired ConcertService concerts;
    @Autowired BookingService booking;
    @MockitoSpyBean QueueService observedQueue;
    @MockitoSpyBean BookingSeatStore seatStore;
    final List<Map<String, Object>> events = new ArrayList<>();

    User user(Role role) {
        String name = UUID.randomUUID().toString();
        return users.save(User.builder().email(name + "@test.invalid").nickname(name).role(role).build());
    }
    String bearer(User user) { return "Bearer " + tokens.generateAccessToken(user, Duration.ofMinutes(10)); }
    Map<String, Object> payload(Concert concert, int total) {
        var data = new HashMap<String, Object>();
        data.put("title", "changed-title"); data.put("eventAt", concert.getEventAt().toString());
        data.put("totalSeats", total); data.put("price", 1000); data.put("posterUrl", "new-poster");
        return data;
    }
    ConcertAdminUpsertRequest request(Concert concert, int total) {
        return json.convertValue(payload(concert, total), ConcertAdminUpsertRequest.class);
    }
    Map<String, Object> snapshot() {
        var state = new LinkedHashMap<String, Object>();
        for (String table : List.of("concert", "booking", "booking_attempt", "payment", "payment_compensation_outbox"))
            state.put(table, jdbcTemplate.queryForList("select * from " + table + " order by id"));
        var redis = new TreeMap<String, String>();
        // KEYS is restricted to a disposable, test-owned Redis. No production observation uses it.
        for (String key : Objects.requireNonNull(redisTemplate.keys("*")))
            redis.put(key, Base64.getEncoder().encodeToString(Objects.requireNonNull(redisTemplate.dump(key))));
        state.put("redis", redis);
        return state;
    }
    void record(String phase) { events.add(Map.of("phase", phase, "snapshot", snapshot())); }

    @ParameterizedTest
    @CsvSource({"OPEN,90", "OPEN,110", "SOLD_OUT,90", "SOLD_OUT,110", "CLOSED,90", "CLOSED,110"})
    void quantityChangesRejectAllStatusesWithoutAnySideEffects(ConcertStatus state, int total) throws Exception {
        Concert c = savedConcert("immutable", 100, 0);
        jdbcTemplate.update("update concert set status=? where id=?", state.name(), c.getId());
        queueService.setConcertInfo(c.getId(), state, c.getTitle(), c.getEventAt());
        User admin = user(Role.ADMIN);
        var before = snapshot(); record("before"); clearInvocations(observedQueue);
        mvc.perform(put("/api/admin/concerts/{id}", c.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload(c, total))))
                .andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);
        verify(observedQueue, never()).getRemainingSeat(anyLong());
        verify(observedQueue, never()).initSeatCount(anyLong(), anyInt());
        verify(observedQueue, never()).setConcertInfo(anyLong(), any(), any(), any());
        record("rejected-no-change");
    }

    @Test void serviceDirectCallRejectsEvenWithoutAttemptHistory() {
        Concert c = savedConcert("direct", 100, 0);
        var before = snapshot(); clearInvocations(observedQueue);
        assertThatThrownBy(() -> concerts.adminUpdate(c.getId(), request(c, 101)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("생성된 공연의 총 티켓 수량은 변경할 수 없습니다.");
        assertThat(snapshot()).isEqualTo(before);
        verify(observedQueue, never()).initSeatCount(anyLong(), anyInt());
    }

    @Test void quantityChangeWithExistingHoldDoesNotTouchHoldOrStock() throws Exception {
        Concert c = savedConcert("held", 100, 0);
        User buyer = user(Role.USER); grantAdmitted(c.getId(), buyer.getId());
        booking.book(c.getId(), buyer.getId(), UUID.randomUUID().toString());
        var before = snapshot();
        mvc.perform(put("/api/admin/concerts/{id}", c.getId()).header("Authorization", bearer(user(Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload(c, 101))))
                .andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(99);
    }

    @Test void unchangedQuantityAllowsMetadataUpdateAndAfterCommitCacheRefresh() {
        Concert c = savedConcert("metadata", 100, 0);
        queueService.setConcertInfo(c.getId(), c.getStatus(), c.getTitle(), c.getEventAt());
        clearInvocations(observedQueue);
        concerts.adminUpdate(c.getId(), request(c, 100));
        assertThat(concertRepository.findById(c.getId()).orElseThrow().getTitle()).isEqualTo("changed-title");
        assertThat(queueService.getConcertInfo(c.getId()).title()).isEqualTo("changed-title");
        assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(100);
        verify(observedQueue, never()).initSeatCount(anyLong(), anyInt());
    }

    @Test void reservationAlreadyDecrementedWhileAdminUpdateIsRejectedKeepsItsTicket() throws Exception {
        Concert c = savedConcert("concurrent", 100, 0);
        User buyer = user(Role.USER), admin = user(Role.ADMIN);
        grantAdmitted(c.getId(), buyer.getId());
        var held = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            held.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("reservation gate timeout");
            return result;
        }).when(seatStore).reserve(any());
        Future<?> pending = executor.submit(() -> booking.book(c.getId(), buyer.getId(), UUID.randomUUID().toString()));
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(99);
            record("redis-held-before-booking-commit"); clearInvocations(observedQueue);
            mvc.perform(put("/api/admin/concerts/{id}", c.getId()).header("Authorization", bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload(c, 110))))
                    .andExpect(status().isConflict());
            verify(observedQueue, never()).initSeatCount(anyLong(), anyInt());
            assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(99);
            finish.countDown(); pending.get(10, TimeUnit.SECONDS);
            assertThat(bookingRepository.count()).isEqualTo(1);
            assertThat(attemptRepository.findAll()).allMatch(a -> a.getStatus() == BookingAttemptStatus.BOOKED);
            assertThat(queueService.getRemainingSeat(c.getId()) + bookingRepository.count()).isEqualTo(100);
            assertThat(concertRepository.findById(c.getId()).orElseThrow().getTotalSeats()).isEqualTo(100);
            record("booking-committed-admin-rejected");
        } finally {
            finish.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"/api/concerts", "/api/admin/concerts"})
    void creationRequiresAdminForBothRoutes(String route) throws Exception {
        var data = Map.of("title", "creation", "eventAt", "2030-01-01T12:00:00", "totalSeats", 100);
        String body = json.writeValueAsString(data); var before = snapshot();
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(route).header("Authorization", bearer(user(Role.USER)))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertThat(snapshot()).isEqualTo(before);
        mvc.perform(post(route).header("Authorization", bearer(user(Role.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        Concert created = concertRepository.findAll().get(0);
        assertThat(queueService.getRemainingSeat(created.getId())).isEqualTo(100);
    }

    @ParameterizedTest
    @CsvSource({"/api/concerts,title", "/api/concerts,eventAt", "/api/concerts,totalSeats",
            "/api/admin/concerts,title", "/api/admin/concerts,eventAt", "/api/admin/concerts,totalSeats"})
    void invalidCreateLeavesNoConcertOrRedisInventory(String route, String field) throws Exception {
        var data = new HashMap<String, Object>(Map.of("title", "invalid", "eventAt", "2030-01-01T12:00:00", "totalSeats", 100));
        if (field.equals("totalSeats")) data.put(field, 0); else data.remove(field);
        var before = snapshot();
        mvc.perform(post(route).header("Authorization", bearer(user(Role.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data))).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }

    @AfterEach void evidence(TestInfo info) throws Exception {
        record("final");
        Path marker = Path.of("test-results/admin-concert-safety/current-run.txt");
        Path dir = Files.exists(marker) ? Path.of(Files.readString(marker).trim()) : Path.of("test-results/admin-concert-safety/current");
        Files.createDirectories(dir.resolve("evidence"));
        Files.writeString(dir.resolve("evidence").resolve(info.getTestMethod().orElseThrow().getName() + "-" + UUID.randomUUID() + ".json"), json.writeValueAsString(events));
    }
}
