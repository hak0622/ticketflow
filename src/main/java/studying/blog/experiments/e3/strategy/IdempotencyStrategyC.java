package studying.blog.experiments.e3.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 전략 C: Redis SETNX (SET if Not eXists) + TTL
 * - 장점: 원자적, DB I/O 없음 → 가장 빠름
 * - 단점: Redis 장애 시 중복 처리 가능, TTL 만료 후 재처리 위험
 */
@Service
@RequiredArgsConstructor
public class IdempotencyStrategyC {

    private final StringRedisTemplate redisTemplate;

    private static final Duration TTL = Duration.ofHours(24);

    public boolean tryAcquire(String eventId) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent("idempotency:" + eventId, "1", TTL);
        return Boolean.TRUE.equals(acquired);
    }
}
