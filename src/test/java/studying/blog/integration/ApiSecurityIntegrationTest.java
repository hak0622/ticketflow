package studying.blog.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import studying.blog.config.TokenProvider;
import studying.blog.domain.Concert;
import studying.blog.domain.Role;
import studying.blog.domain.User;
import studying.blog.repository.UserRepository;
import studying.blog.support.IntegrationTestSupport;

import java.time.Duration;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ApiSecurityIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenProvider tokenProvider;

    @Test
    void bookingApi_withoutToken_returns401Json() throws Exception {
        Concert concert = savedConcert("Protected Booking Concert", 10, 0);

        mockMvc.perform(post("/api/concerts/{concertId}/booking", concert.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void bookingApi_withInvalidToken_returns401Json() throws Exception {
        Concert concert = savedConcert("Invalid Token Booking Concert", 10, 0);

        mockMvc.perform(post("/api/concerts/{concertId}/booking", concert.getId())
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void paymentApi_withoutToken_returns401Json() throws Exception {
        Concert concert = savedConcert("Protected Payment Concert", 10, 0);

        mockMvc.perform(post("/api/concerts/{concertId}/payment", concert.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest(UUID.randomUUID().toString()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void queueApi_withoutToken_returns401Json() throws Exception {
        Concert concert = savedConcert("Protected Queue Concert", 10, 0);

        mockMvc.perform(post("/api/concerts/{concertId}/queue", concert.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void publicConcertApis_remainAccessibleWithoutToken() throws Exception {
        Concert concert = savedConcert("Public Concert", 10, 0);

        mockMvc.perform(get("/api/concerts"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/concerts/{id}", concert.getId()))
                .andExpect(status().isOk());
    }

    @Test
    void adminApi_withUserToken_returns403Json() throws Exception {
        User user = savedUser("security-user", Role.USER);

        mockMvc.perform(get("/api/admin/concerts")
                        .header("Authorization", authorizationHeader(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    private User savedUser(String prefix, Role role) {
        String uniq = prefix + "-" + System.nanoTime();
        return userRepository.save(User.builder()
                .email(uniq + "@test.com")
                .password("password")
                .nickname(uniq)
                .role(role)
                .build());
    }

    private String authorizationHeader(User user) {
        return "Bearer " + tokenProvider.generateToken(user, Duration.ofHours(1));
    }
}
