package studying.blog.experiments.e1.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.experiments.e1.domain.CouponIssue;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    long countByCouponId(Long couponId);

    @Modifying
    @Transactional
    @Query("delete from CouponIssue c where c.couponId = :couponId")
    void deleteByCouponId(@Param("couponId") Long couponId);
}
