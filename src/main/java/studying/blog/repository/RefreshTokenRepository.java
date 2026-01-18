package studying.blog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import studying.blog.domain.RefreshToken;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken,Long> {
    Optional<RefreshToken>findByUserId(Long userId);
    Optional<RefreshToken>findByRefreshToken(String refreshToken);
}
