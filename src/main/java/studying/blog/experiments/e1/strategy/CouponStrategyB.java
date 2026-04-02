package studying.blog.experiments.e1.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.experiments.e1.domain.Coupon;
import studying.blog.experiments.e1.domain.CouponIssue;
import studying.blog.experiments.e1.repository.CouponIssueRepository;
import studying.blog.experiments.e1.repository.CouponRepository;

/**
 * 전략 B: MySQL Pessimistic Lock (SELECT FOR UPDATE)
 * - Coupon 행에 쓰기 락 획득 후 재고 차감
 * - DB 직렬화 보장 → 초과 발급 없음
 * - 동시성 높을수록 락 대기 증가 → TPS 저하 예상
 */
@Service
@RequiredArgsConstructor
public class CouponStrategyB {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Transactional
    public void issue(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findByIdWithLock(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰 없음: " + couponId));

        coupon.increaseIssuedCount(); // 재고 없으면 IllegalStateException
        couponIssueRepository.save(CouponIssue.create(couponId, userId));
    }
}
