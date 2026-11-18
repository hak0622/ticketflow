package studying.blog.config.oauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class OAuthLoginExchange {
    private static final Duration EXPIRY = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final String frontendOrigin;

    public OAuthLoginExchange(StringRedisTemplate redis, @Value("${oauth2.redirect-url}") String redirectUrl) {
        this.redis = redis;
        URI uri = URI.create(redirectUrl);
        String scheme = uri.getScheme();
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("oauth2.redirect-url must be an HTTP(S) frontend URL");
        }

        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        this.frontendOrigin = UriComponentsBuilder.newInstance()
                .scheme(scheme).host(uri.getHost().toLowerCase(Locale.ROOT)).port(port).build().toUriString();
    }

    public String frontendOrigin() {
        return frontendOrigin;
    }

    public String callbackUrl() {
        return frontendOrigin + "/auth/callback";
    }

    public String loginErrorUrl() {
        return frontendOrigin + "/login?error=oauth";
    }

    public void prepare(String sessionId, Long userId) {
        redis.opsForValue().set(key(sessionId, userId), userId.toString(), EXPIRY);
    }

    public boolean consume(String sessionId, Long userId) {
        return userId.toString().equals(redis.opsForValue().getAndDelete(key(sessionId, userId)));
    }

    private String key(String sessionId, Long userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return "oauth:exchange:" + HexFormat.of().formatHex(digest) + ":" + userId;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
