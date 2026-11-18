package studying.blog.config.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import studying.blog.config.TokenProvider;
import studying.blog.domain.RefreshToken;
import studying.blog.repository.RefreshTokenRepository;
import studying.blog.util.CookieUtil;
import studying.blog.service.UserService;

import java.io.IOException;
import java.time.Duration;

@RequiredArgsConstructor
@Slf4j
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    public static final Duration ACCESS_TOKEN_DURATION = Duration.ofDays(1);

    private final TokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserService userService;
    private final OAuthLoginExchange exchange;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException{
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        clearAuthenticationAttributes(request);
        try {
            OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
            var user = userService.findByEmail(oauthUser.getAttribute("email"));
            Duration lifetime = Duration.ofDays(14);
            String refreshToken = tokenProvider.generateRefreshToken(user, lifetime);
            var record = refreshTokenRepository.findByUserId(user.getId())
                    .map(existing -> existing.update(refreshToken))
                    .orElseGet(() -> new RefreshToken(user.getId(), refreshToken));
            refreshTokenRepository.save(record);
            exchange.prepare(request.getSession().getId(), user.getId());
            CookieUtil.addCookie(response, "refresh_token", refreshToken, (int) lifetime.toSeconds());
        } catch (DataAccessException e) {
            log.warn("Could not prepare OAuth login exchange ({})", e.getClass().getSimpleName());
            getRedirectStrategy().sendRedirect(request, response, exchange.loginErrorUrl());
            return;
        }
        getRedirectStrategy().sendRedirect(request, response, exchange.callbackUrl());
    }
}
