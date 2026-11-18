package studying.blog.config.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import studying.blog.config.CustomPrincipal;
import studying.blog.config.JwtProperties;
import studying.blog.config.TokenProvider;
import studying.blog.domain.User;
import studying.blog.repository.UserRepository;

import java.time.Duration;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class TokenProviderTest {
    @Autowired private TokenProvider tokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtProperties jwtProperties;

    @DisplayName("접근 토큰에 사용자 ID와 접근 용도를 저장한다.")
    @Test
    void generateAccessToken(){
        User testUser = userRepository.save(User.builder()
                .email("user@gamil.com")
                .password("test")
                .build());

        String token = tokenProvider.generateAccessToken(testUser, Duration.ofDays(1));

        var claims = Jwts.parserBuilder()
                .setSigningKey(jwtProperties.signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();

        assertThat(claims.get("id", Long.class)).isEqualTo(testUser.getId());
        assertThat(claims.get("token_use", String.class)).isEqualTo("access");
    }

    @DisplayName("만료된 접근 토큰은 인증 객체를 만들지 못한다.")
    @Test
    void expiredAccessTokenIsRejected(){
        String token = JwtFactory.builder()
                .expiration(new Date(new Date().getTime() - Duration.ofDays(7).toMillis()))
                .claims(Map.of("id", 1L, "token_use", "access"))
                .build()
                .createToken(jwtProperties);

        assertThatThrownBy(() -> tokenProvider.getAccessAuthentication(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @DisplayName("getAuthentication() : 토큰 기반으로 인증 정보를 가져올 수 있다.")
    @Test
    void getAuthentication(){
        String userEmail = "user@gmail.com";
        String token = JwtFactory.builder()
                .subject(userEmail)
                .claims(Map.of("id", 1L, "role", "USER", "token_use", "access"))
                .build()
                .createToken(jwtProperties);

        Authentication authentication = tokenProvider.getAccessAuthentication(token);
        CustomPrincipal principal = (CustomPrincipal) authentication.getPrincipal();
        assertThat(principal.getUsername()).isEqualTo(userEmail);
        assertThat(principal.getUserId()).isEqualTo(1L);
    }

    @DisplayName("발급한 갱신 토큰은 갱신 검증을 통과한다.")
    @Test
    void generateRefreshToken(){
        User user = userRepository.save(User.builder().email("a".repeat(64) + "@example.test").build());
        String token = tokenProvider.generateRefreshToken(user, Duration.ofDays(14));
        tokenProvider.validateRefreshToken(token);
        assertThat(token.length()).isLessThanOrEqualTo(255);
        var claims = Jwts.parserBuilder().setSigningKey(jwtProperties.signingKey()).build()
                .parseClaimsJws(token).getBody();
        assertThat(claims.get("id", Long.class)).isEqualTo(user.getId());
        assertThat(claims.getSubject()).isNull();
        assertThat(claims.get("role")).isNull();
    }
}
