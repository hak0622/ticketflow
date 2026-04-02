package studying.blog.experiments.e1.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.experiments.e1.domain.Coupon;
import studying.blog.experiments.e1.domain.CouponIssue;
import studying.blog.experiments.e1.repository.CouponIssueRepository;
import studying.blog.experiments.e1.repository.CouponRepository;

/**
 * 전략 C: MySQL Optimistic Lock (@Version)
 * - 락 없이 읽고, 커밋 시 version 충돌 감지
 * - 충돌 시 ObjectOptimisticLockingFailureException → 호출부에서 재시도
 * - 충돌 낮으면 TPS 높음, 충돌 많으면 재시도 폭발
 */
@Service
@RequiredArgsConstructor
public class CouponStrategyC {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Transactional
    public void issueOnce(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰 없음: " + couponId));

        coupon.increaseIssuedCount(); // 재고 없으면 IllegalStateException
        couponIssueRepository.save(CouponIssue.create(couponId, userId));
        // 트랜잭션 커밋 시 @Version 불일치면 ObjectOptimisticLockingFailureException 발생
    }
}
