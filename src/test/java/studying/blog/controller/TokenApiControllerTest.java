package studying.blog.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import studying.blog.config.JwtProperties;
import studying.blog.config.TokenProvider;
import studying.blog.config.jwt.JwtFactory;
import studying.blog.domain.RefreshToken;
import studying.blog.domain.User;
import studying.blog.repository.RefreshTokenRepository;
import studying.blog.repository.UserRepository;
import studying.blog.service.RefreshTokenService;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class TokenApiControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @MockitoSpyBean private RefreshTokenService refreshTokenService;

    private User user;

    @BeforeEach
    void saveUser() {
        String unique = String.valueOf(System.nanoTime());
        user = userRepository.save(User.builder()
                .email("user" + unique + "@example.test").nickname("nick" + unique).build());
    }

    @Test
    void onlyTheRegisteredRefreshTokenIssuesAnAccessToken() throws Exception {
        String refreshToken = tokenProvider.generateRefreshToken(user, Duration.ofDays(14));
        refreshTokenRepository.save(new RefreshToken(user.getId(), refreshToken));

        var response = reissue(refreshToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse();

        String accessToken = objectMapper.readTree(response.getContentAsString()).get("accessToken").asText();
        assertThat(tokenProvider.getAccessAuthentication(accessToken).getName()).isEqualTo(user.getEmail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"access", "legacy", "unknown", "expired", "malformed"})
    void invalidJwtIsRejectedBeforeCheckingTheStoredToken(String type) throws Exception {
        String token = switch (type) {
            case "access" -> JwtFactory.builder().subject("u@x.test")
                    .claims(Map.of("id", user.getId(), "token_use", "access")).build().createToken(jwtProperties);
            case "legacy" -> JwtFactory.builder().claims(Map.of("id", user.getId())).build().createToken(jwtProperties);
            case "unknown" -> JwtFactory.builder().claims(Map.of("id", user.getId(), "token_use", "unknown"))
                    .build().createToken(jwtProperties);
            case "expired" -> tokenProvider.generateRefreshToken(user, Duration.ofMinutes(-1));
            case "malformed" -> "invalid-token";
            default -> throw new AssertionError(type);
        };
        refreshTokenRepository.save(new RefreshToken(user.getId(), token));

        reissue(token).andExpect(status().isBadRequest());
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void unregisteredAndReplacedRefreshTokensAreRejected() throws Exception {
        String old = tokenProvider.generateRefreshToken(user, Duration.ofDays(13));
        String replacement = tokenProvider.generateRefreshToken(user, Duration.ofDays(14));
        reissue(old).andExpect(status().isBadRequest());

        var record = refreshTokenRepository.save(new RefreshToken(user.getId(), old));
        refreshTokenRepository.save(record.update(replacement));
        reissue(old).andExpect(status().isBadRequest());
        reissue(replacement).andExpect(status().isCreated());
    }

    private ResultActions reissue(String token) throws Exception {
        return mockMvc.perform(post("/api/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", token))));
    }
}
