package studying.blog.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import studying.blog.domain.RefreshToken;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken,Long> {
    Optional<RefreshToken>findByUserId(Long userId);
    Optional<RefreshToken>findByRefreshToken(String refreshToken);

    @Query("select token.userId from RefreshToken token where token.refreshToken = :refreshToken")
    Optional<Long> findUserIdByRefreshToken(String refreshToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshToken token where token.userId = :userId")
    Optional<RefreshToken> findForUpdateByUserId(Long userId);
}
