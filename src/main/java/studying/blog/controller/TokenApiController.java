package studying.blog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import studying.blog.config.oauth.OAuthLoginExchange;
import studying.blog.config.oauth.RefreshTokenCookie;
import studying.blog.dto.CreateAccessTokenResponse;
import studying.blog.service.TokenService;

import java.util.Collections;
import java.util.List;

@Tag(name = "Auth", description = "인증 API")
@RequiredArgsConstructor
@RestController
@Slf4j
public class TokenApiController {
    private final TokenService tokenService;
    private final OAuthLoginExchange login;

    @Operation(summary = "액세스 토큰 재발급", description = "갱신 쿠키로 새 액세스 토큰을 발급합니다.")
    @PostMapping("/api/token")
    public ResponseEntity<CreateAccessTokenResponse> createNewAccessToken(HttpServletRequest request,
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (!Collections.list(request.getHeaders(HttpHeaders.ORIGIN)).equals(List.of(login.frontendOrigin()))) {
            return reject(HttpStatus.FORBIDDEN);
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            return reject(HttpStatus.UNAUTHORIZED);
        }
        try {
            String accessToken = tokenService.createNewAccessToken(refreshToken);
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(new CreateAccessTokenResponse(accessToken));
        } catch (IllegalArgumentException e) {
            return reject(HttpStatus.UNAUTHORIZED);
        } catch (DataAccessException e) {
            log.warn("Access token renewal is unavailable ({})", e.getClass().getSimpleName());
            return reject(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private ResponseEntity<CreateAccessTokenResponse> reject(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }
}
