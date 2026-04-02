package studying.blog.experiments.e1.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import studying.blog.experiments.e1.domain.CouponIssue;
import studying.blog.experiments.e1.repository.CouponIssueRepository;

/**
 * 전략 A: Redis DECR 원자적 재고 선점
 * - Redis key: coupon:stock:{couponId}
 * - DECR 후 음수면 INCR로 복구 (초과 발급 방지)
 * - DB 락 없음 → 높은 TPS 기대
 */
@Service
@RequiredArgsConstructor
public class CouponStrategyA {

    private final StringRedisTemplate redisTemplate;
    private final CouponIssueRepository couponIssueRepository;

    public static String stockKey(Long couponId) {
        return "coupon:stock:" + couponId;
    }

    public void issue(Long couponId, Long userId) {
        String key = stockKey(couponId);
        Long remaining = redisTemplate.opsForValue().decrement(key);

        if (remaining == null || remaining < 0) {
            redisTemplate.opsForValue().increment(key); // 롤백
            throw new IllegalStateException("재고 없음");
        }

        couponIssueRepository.save(CouponIssue.create(couponId, userId));
    }
}
