package studying.blog.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import studying.blog.support.IntegrationTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("integration")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.test-support.enabled=true",
        "app.test-support.user-count=1",
        "app.test-support.email-prefix=isolated-admin-support-",
        "app.test-support.email-domain=test.invalid"
})
class AdminTestSupportOptInIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @TempDir static Path csvDirectory;

    @DynamicPropertySource
    static void isolatedCsv(DynamicPropertyRegistry registry) {
        registry.add("app.test-support.csv-output-path", () -> csvDirectory.resolve("users.csv").toString());
    }

    @Test void explicitOptInPreservesJwtCsvAndSetupResetOnDisposableDataOnly() throws Exception {
        // Tokens/CSV remain in ignored raw logs; never copy response bodies into public documents.
        var byEmail = mvc.perform(post("/api/test-support/jwt/by-email").contentType("application/json")
                        .content("{\"email\":\"isolated-admin-support-1@test.invalid\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var issued = json.readTree(byEmail);
        assertCanAccessBookings(issued.path("accessToken").asText());

        var byId = mvc.perform(post("/api/test-support/jwt/by-user-id").contentType("application/json")
                        .content(json.createObjectNode().put("userId", issued.path("userId").asLong()).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertCanAccessBookings(json.readTree(byId).path("accessToken").asText());

        var csv = mvc.perform(get("/api/test-support/csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertCanAccessBookings(csv.lines().skip(1).findFirst().orElseThrow().split(",")[2]);
        assertCanAccessBookings(Files.readAllLines(csvDirectory.resolve("users.csv")).get(1).split(",")[2]);
        var concert = savedConcert("isolated-support", 10, 0);
        mvc.perform(post("/api/test-support/concerts/{id}/setup", concert.getId()).param("mode", "BOOKING"))
                .andExpect(status().isOk());
        assertThat(redisTemplate.keys("admitted:concert:" + concert.getId() + ":user:*")).hasSize(1);
        mvc.perform(post("/api/test-support/concerts/{id}/reset", concert.getId())).andExpect(status().isOk());
        assertThat(redisTemplate.keys("admitted:concert:" + concert.getId() + ":user:*")).isEmpty();
        assertThat(redisTemplate.hasKey("seats:concert:" + concert.getId())).isFalse();
    }

    private void assertCanAccessBookings(String token) throws Exception {
        mvc.perform(get("/api/me/bookings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
