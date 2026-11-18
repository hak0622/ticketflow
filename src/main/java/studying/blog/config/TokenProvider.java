package studying.blog.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import studying.blog.domain.Role;
import studying.blog.domain.User;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Set;

@RequiredArgsConstructor
@Service
public class TokenProvider {
    private static final String TOKEN_USE = "token_use";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtProperties jwtProperties;

    public String generateAccessToken(User user, Duration duration) {
        String role = (user.getRole() == null) ? Role.USER.name() : user.getRole().name();
        Date now = new Date();
        return tokenBuilder(user.getId(), new Date(now.getTime() + duration.toMillis()), ACCESS)
                .setIssuedAt(now)
                .setSubject(user.getEmail())
                .claim("role", role)
                .compact();
    }

    public String generateRefreshToken(User user, Duration duration) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return tokenBuilder(user.getId(), new Date(System.currentTimeMillis() + duration.toMillis()), REFRESH)
                .setId(Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
                .compact();
    }

    private JwtBuilder tokenBuilder(Long userId, Date expiry, String tokenUse) {
        return Jwts.builder()
                .setHeaderParam(Header.TYPE, Header.JWT_TYPE)
                .setIssuer(jwtProperties.getIssuer())
                .setExpiration(expiry)
                .claim("id", userId)
                .claim(TOKEN_USE, tokenUse)
                .signWith(SignatureAlgorithm.HS256, jwtProperties.getSecretKey());
    }

    public Authentication getAccessAuthentication(String token) {
        Claims claims = parseToken(token, ACCESS);
        Long userId = claims.get("id", Long.class);
        String email = claims.getSubject();
        if (userId == null || userId <= 0 || email == null || email.isBlank()) {
            throw new JwtException("Invalid access token identity");
        }

        String role = claims.get("role", String.class);
        if (role == null || role.isBlank()) {
            role = Role.USER.name();
        }
        var authorities = Set.of(new SimpleGrantedAuthority("ROLE_" + role));
        var principal = CustomPrincipal.of(userId, email, authorities);
        return new UsernamePasswordAuthenticationToken(principal, token, authorities);
    }

    public void validateRefreshToken(String token) {
        parseToken(token, REFRESH);
    }

    private Claims parseToken(String token, String tokenUse) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(jwtProperties.getSecretKey())
                .requireIssuer(jwtProperties.getIssuer())
                .require(TOKEN_USE, tokenUse)
                .build()
                .parseClaimsJws(token)
                .getBody();
        Date expiry = claims.getExpiration();
        if (expiry == null || !expiry.after(new Date())) {
            throw new JwtException("Missing or expired token expiration");
        }
        return claims;
    }
}
