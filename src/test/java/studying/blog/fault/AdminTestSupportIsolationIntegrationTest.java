package studying.blog.fault;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import studying.blog.domain.Concert;
import studying.blog.support.IntegrationTestSupport;
import studying.blog.testsupport.*;


import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.test-support.enabled=false")
class AdminTestSupportIsolationIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test void disabledSupportHasNoControllerRunnerOrServicesAndCannotIssueTokensOrResetData() throws Exception {
        for (Class<?> type : new Class<?>[]{TestJwtSupportController.class, TestJwtSupportService.class,
                TestConcertSupportService.class, TestUserSeedService.class, TestJwtCommandLineRunner.class})
            assertThat(context.getBeanNamesForType(type)).isEmpty();
        Concert c = savedConcert("must-preserve", 10, 0);
        var booking = savedPendingBooking(c, 77L);
        var concertBefore = jdbcTemplate.queryForList("select * from concert");
        var attemptsBefore = jdbcTemplate.queryForList("select * from booking_attempt");
        var stockBefore = redisTemplate.opsForValue().get("seats:concert:" + c.getId());
        var hashBefore = redisTemplate.opsForHash().entries("booking:attempt:" + attemptRepository.findByBookingId(booking.getId()).orElseThrow().getId());
        for (String route : new String[]{"/api/test-support/jwt/by-email", "/api/test-support/jwt/by-user-id",
                "/api/test-support/concerts/" + c.getId() + "/setup", "/api/test-support/concerts/" + c.getId() + "/reset"})
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        mvc.perform(get("/api/test-support/csv")).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForList("select * from concert")).isEqualTo(concertBefore);
        assertThat(jdbcTemplate.queryForList("select * from booking_attempt")).isEqualTo(attemptsBefore);
        assertThat(redisTemplate.opsForValue().get("seats:concert:" + c.getId())).isEqualTo(stockBefore);
        assertThat(redisTemplate.opsForHash().entries("booking:attempt:" + attemptRepository.findByBookingId(booking.getId()).orElseThrow().getId())).isEqualTo(hashBefore);
        assertThat(bookingRepository.findById(booking.getId())).isPresent();
    }
}
