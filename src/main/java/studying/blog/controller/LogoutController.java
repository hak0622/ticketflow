package studying.blog.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import studying.blog.config.oauth.OAuthLoginExchange;
import studying.blog.config.oauth.RefreshTokenCookie;
import studying.blog.service.RefreshTokenService;

import java.util.Collections;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class LogoutController {
    private final OAuthLoginExchange login;
    private final RefreshTokenService refreshTokens;
    private final RefreshTokenCookie cookie;

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
                                       Authentication authentication,
                                       @CookieValue(name = RefreshTokenCookie.NAME, required = false) String token) {
        if (!Collections.list(request.getHeaders(HttpHeaders.ORIGIN)).equals(List.of(login.frontendOrigin()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).build();
        }

        try {
            if (token != null && !token.isBlank()) {
                refreshTokens.revoke(token);
            }
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            cookie.clear(response);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } catch (DataAccessException e) {
            log.warn("Logout could not complete ({})", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).build();
        }
    }
}
