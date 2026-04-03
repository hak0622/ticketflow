package studying.blog.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.repository.CouponIssueRepository;
import studying.blog.repository.CouponRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CouponIssueRepositoryTest {

    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponIssueRepository couponIssueRepository;

    private Coupon coupon;

    @BeforeEach
    void setUp() {
        coupon = couponRepository.save(Coupon.builder()
                .code("TEST10")
                .discountAmount(1000)
                .totalQty(10)
                .build());
    }

    @Test
    void save_and_exists() {
        couponIssueRepository.save(CouponIssue.create(coupon, 1L));

        assertThat(couponIssueRepository.existsByCouponAndUserId(coupon, 1L)).isTrue();
        assertThat(couponIssueRepository.existsByCouponAndUserId(coupon, 2L)).isFalse();
    }

    @Test
    void duplicate_throws() {
        couponIssueRepository.saveAndFlush(CouponIssue.create(coupon, 1L));

        assertThatThrownBy(() ->
                couponIssueRepository.saveAndFlush(CouponIssue.create(coupon, 1L))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}
