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
import studying.blog.service.RefreshTokenService;
import studying.blog.service.UserService;

import java.io.IOException;
import java.time.Duration;

@RequiredArgsConstructor
@Slf4j
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    public static final Duration ACCESS_TOKEN_DURATION = Duration.ofDays(1);

    private final TokenProvider tokenProvider;
    private final RefreshTokenService refreshTokens;
    private final RefreshTokenCookie refreshCookie;
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
            String refreshToken = tokenProvider.generateRefreshToken(user, RefreshTokenCookie.LIFETIME);
            refreshTokens.saveForLogin(user.getId(), refreshToken);
            exchange.prepare(request.getSession().getId(), user.getId());
            refreshCookie.issue(response, refreshToken);
        } catch (DataAccessException e) {
            log.warn("Could not prepare OAuth login exchange ({})", e.getClass().getSimpleName());
            getRedirectStrategy().sendRedirect(request, response, exchange.loginErrorUrl());
            return;
        }
        getRedirectStrategy().sendRedirect(request, response, exchange.callbackUrl());
    }
}
