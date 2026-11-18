package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.RefreshToken;
import studying.blog.repository.RefreshTokenRepository;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshToken findByRefreshToken(String refreshToken){
        return refreshTokenRepository.findByRefreshToken(refreshToken)
                .filter(record -> refreshToken.equals(record.getRefreshToken()))
                .orElseThrow(() -> new IllegalArgumentException("unexpected token"));
    }

    @Transactional
    public void saveForLogin(Long userId, String token) {
        var record = refreshTokenRepository.findForUpdateByUserId(userId)
                .map(existing -> existing.update(token))
                .orElseGet(() -> new RefreshToken(userId, token));
        refreshTokenRepository.save(record);
    }

    @Transactional
    public void revoke(String token) {
        refreshTokenRepository.findUserIdByRefreshToken(token)
                .flatMap(refreshTokenRepository::findForUpdateByUserId)
                .filter(record -> token.equals(record.getRefreshToken()))
                .ifPresent(refreshTokenRepository::delete);
    }
}
