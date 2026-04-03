package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Coupon;
import studying.blog.domain.CouponIssue;
import studying.blog.repository.CouponIssueRepository;
import studying.blog.repository.CouponRepository;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final StringRedisTemplate redisTemplate;

    public static String stockKey(Long couponId) {
        return "coupon:stock:" + couponId;
    }

    public void initStock(Long couponId, int qty) {
        redisTemplate.opsForValue().set(stockKey(couponId), String.valueOf(qty));
    }

    @Transactional
    public CouponIssue issue(String code, Long userId) {
        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰: " + code));

        if (couponIssueRepository.existsByCouponAndUserId(coupon, userId)) {
            throw new IllegalStateException("이미 발급된 쿠폰");
        }

        String key = stockKey(coupon.getId());
        Long remaining = redisTemplate.opsForValue().decrement(key);
        if (remaining == null || remaining < 0) {
            redisTemplate.opsForValue().increment(key);
            throw new IllegalStateException("재고 없음");
        }

        coupon.increaseIssuedCount();
        return couponIssueRepository.save(CouponIssue.create(coupon, userId));
    }
}
