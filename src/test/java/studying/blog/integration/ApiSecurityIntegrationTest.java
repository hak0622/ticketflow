package studying.blog.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import studying.blog.config.JwtProperties;
import studying.blog.config.TokenProvider;
import studying.blog.domain.Concert;
import studying.blog.domain.Role;
import studying.blog.domain.User;
import studying.blog.repository.UserRepository;
import studying.blog.support.IntegrationTestSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Tag("integration")
class ApiSecurityIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenProvider tokenProvider;
    @Autowired
    private JwtProperties jwtProperties;

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

    @Test
    void adminApiRejectsAdminRefreshTokenAndAcceptsAdminAccessToken() throws Exception {
        User admin = savedUser("security-admin", Role.ADMIN);
        assertAdminUnauthorized(tokenProvider.generateRefreshToken(admin, Duration.ofDays(14)));
        mockMvc.perform(get("/api/admin/concerts").header("Authorization", authorizationHeader(admin)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing purpose", "unknown purpose", "expired", "missing expiration",
            "missing issuer", "wrong issuer", "wrong signature"})
    void adminApiRejectsInvalidClaimsAndSignatures(String failure) throws Exception {
        User admin = savedUser("security-invalid", Role.ADMIN);
        var builder = Jwts.builder()
                .setSubject(admin.getEmail())
                .setIssuer(jwtProperties.getIssuer())
                .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
                .claim("id", admin.getId()).claim("role", Role.ADMIN.name()).claim("token_use", "access");
        switch (failure) {
            case "missing purpose" -> builder.claim("token_use", null);
            case "unknown purpose" -> builder.claim("token_use", "unknown");
            case "expired" -> builder.setExpiration(Date.from(Instant.now().minusSeconds(60)));
            case "missing expiration" -> builder.setExpiration(null);
            case "missing issuer" -> builder.setIssuer(null);
            case "wrong issuer" -> builder.setIssuer("another-issuer");
            case "wrong signature" -> { }
            default -> throw new AssertionError(failure);
        }
        var key = failure.equals("wrong signature") ? Keys.secretKeyFor(SignatureAlgorithm.HS256)
                : jwtProperties.signingKey();
        assertAdminUnauthorized(builder.signWith(key, SignatureAlgorithm.HS256).compact());
    }

    private void assertAdminUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/api/admin/concerts").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
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
        return "Bearer " + tokenProvider.generateAccessToken(user, Duration.ofHours(1));
    }
}
