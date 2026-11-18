package studying.blog.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import studying.blog.config.TokenProvider;
import studying.blog.config.oauth.OAuth2SuccessHandler;
import studying.blog.config.oauth.OAuthLoginExchange;
import studying.blog.dto.CreateAccessTokenResponse;
import studying.blog.repository.UserRepository;

import java.util.Collections;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class OAuthExchangeController {
    private final OAuthLoginExchange exchange;
    private final TokenProvider tokens;
    private final UserRepository users;

    @PostMapping("/oauth2/exchange")
    public ResponseEntity<CreateAccessTokenResponse> exchange(HttpServletRequest request, Authentication authentication) {
        var origins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (!origins.equals(List.of(exchange.frontendOrigin()))) {
            return reject(HttpStatus.FORBIDDEN);
        }

        var session = request.getSession(false);
        if (session == null || !(authentication instanceof OAuth2AuthenticationToken oauth)
                || !oauth.isAuthenticated() || !"google".equals(oauth.getAuthorizedClientRegistrationId())) {
            return reject(HttpStatus.UNAUTHORIZED);
        }

        String email = oauth.getPrincipal().getAttribute("email");
        if (email == null || email.isBlank()) {
            return reject(HttpStatus.UNAUTHORIZED);
        }

        try {
            var user = users.findByEmail(email).orElse(null);
            if (user == null || !exchange.consume(session.getId(), user.getId())) {
                return reject(HttpStatus.UNAUTHORIZED);
            }

            var accessToken = tokens.generateAccessToken(user, OAuth2SuccessHandler.ACCESS_TOKEN_DURATION);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new CreateAccessTokenResponse(accessToken));
        } catch (DataAccessException e) {
            log.warn("OAuth login exchange is unavailable ({})", e.getClass().getSimpleName());
            return reject(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private ResponseEntity<CreateAccessTokenResponse> reject(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }
}
