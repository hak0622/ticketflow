package studying.blog.service;

import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import studying.blog.config.TokenProvider;
import studying.blog.domain.User;

import java.time.Duration;

@RequiredArgsConstructor
@Service
public class TokenService {
    private final TokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;

    public String createNewAccessToken(String refreshToken){
        try {
            tokenProvider.validateRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Unexpected token", e);
        }

        Long userId = refreshTokenService.findByRefreshToken(refreshToken).getUserId();
        User user = userService.findById(userId);

        return tokenProvider.generateAccessToken(user, Duration.ofHours(2));
    }
}
