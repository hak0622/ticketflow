package studying.blog.config;

import io.jsonwebtoken.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import studying.blog.domain.Role;
import studying.blog.domain.User;

import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.Set;

@RequiredArgsConstructor
@Service
public class TokenProvider {
    private final JwtProperties jwtProperties;

    public String generateToken(User user, Duration expriedAt){
        Date now = new Date();
        return makeToken(new Date(now.getTime()+expriedAt.toMillis()),user);
    }

    private String makeToken(Date expiry,User user){
        Date now = new Date();

        String role = (user.getRole() == null) ? Role.USER.name() : user.getRole().name();

        return Jwts.builder()
                .setHeaderParam(Header.TYPE,Header.JWT_TYPE)
                .setIssuer(jwtProperties.getIssuer())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .setSubject(user.getEmail())
                .claim("id",user.getId())
                .claim("role",role)
                .signWith(SignatureAlgorithm.HS256, jwtProperties.getSecretKey())
                .compact();
    }

    public boolean validToken(String token){
        try{
            Jwts.parser()
                    .setSigningKey(jwtProperties.getSecretKey())
                    .parseClaimsJws(token);
            return true;
        }catch (Exception e){
            return false;
        }
    }

    public Authentication getAuthentication(String token){
        Claims claims = getClaims(token);

        Long userId = claims.get("id",Long.class);
        String email = claims.getSubject();

        String role = claims.get("role",String.class);
        if(role == null || role.isBlank()) role = Role.USER.name();

        Set<SimpleGrantedAuthority>authorities = Collections.singleton(new SimpleGrantedAuthority("ROLE_" + role));

        CustomPrincipal principal = new CustomPrincipal(userId, email);

        return new UsernamePasswordAuthenticationToken(principal,token,authorities);
    }

    public Long getUserId(String token){
        Claims claims = getClaims(token);
        return claims.get("id", Long.class);
    }

    public Claims getClaims(String token){
        return Jwts.parser()
                .setSigningKey(jwtProperties.getSecretKey())
                .parseClaimsJws(token)
                .getBody();
    }
}
