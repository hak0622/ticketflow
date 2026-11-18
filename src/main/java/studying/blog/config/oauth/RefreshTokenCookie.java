package studying.blog.config.oauth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RefreshTokenCookie {
    public static final String NAME = "refresh_token";
    public static final Duration LIFETIME = Duration.ofDays(14);

    private final boolean secure;

    public RefreshTokenCookie(OAuthLoginExchange exchange) {
        secure = exchange.frontendOrigin().startsWith("https://");
    }

    public void issue(HttpServletResponse response, String token) {
        write(response, token, LIFETIME);
    }

    public void clear(HttpServletResponse response) {
        write(response, "", Duration.ZERO);
    }

    private void write(HttpServletResponse response, String token, Duration maxAge) {
        var cookie = ResponseCookie.from(NAME, token)
                .httpOnly(true).sameSite("Lax").secure(secure).path("/").maxAge(maxAge).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
