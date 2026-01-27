package studying.blog.config;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

@Getter
@Setter
@Component
@ConfigurationProperties("jwt")
public class JwtProperties {
    private String issuer;
    private String secretKey;

    public SecretKey signingKey() {
        if (secretKey == null) {
            throw new IllegalStateException("jwt.secretKey is null (JWT_SECRET_KEY 환경변수가 설정되지 않았을 수 있음)");
        }

        String s = secretKey.trim()
                .replace("\"", "")
                .replace("'", "");

        try {
            byte[] keyBytes = (s.contains("_") || s.contains("-"))
                    ? Decoders.BASE64URL.decode(s)
                    : Decoders.BASE64.decode(s);

            return Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            String preview = s.length() <= 12 ? s : s.substring(0, 6) + "..." + s.substring(s.length() - 6);
            throw new IllegalStateException("JWT_SECRET_KEY 디코딩 실패. 값이 Base64/Base64URL 형식인지 확인: " + preview, e);
        }
    }
}
