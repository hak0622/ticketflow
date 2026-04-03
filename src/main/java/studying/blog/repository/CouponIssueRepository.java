package studying.blog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import studying.blog.domain.Coupon;
import studying.blog.domain.CouponIssue;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {
    boolean existsByCouponAndUserId(Coupon coupon, Long userId);
}
