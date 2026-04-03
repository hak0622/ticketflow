package studying.blog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Coupon;
import studying.blog.domain.CouponIssue;
import studying.blog.repository.CouponRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CouponServiceTest {

    @Autowired private CouponService couponService;
    @Autowired private CouponRepository couponRepository;

    @MockBean private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOps;
    private Coupon coupon;

    @BeforeEach
    void setUp() {
        valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.decrement(anyString())).willReturn(9L); // 재고 충분

        coupon = couponRepository.save(Coupon.builder()
                .code("WELCOME5000")
                .discountAmount(5000)
                .totalQty(10)
                .build());
    }

    @Test
    void issue_success() {
        CouponIssue issue = couponService.issue("WELCOME5000", 1L);

        assertThat(issue.getId()).isNotNull();
        assertThat(issue.getCoupon().getId()).isEqualTo(coupon.getId());
        assertThat(issue.getUserId()).isEqualTo(1L);
        assertThat(issue.getIssuedAt()).isNotNull();
    }

    @Test
    void issue_already_issued() {
        couponService.issue("WELCOME5000", 1L);

        assertThatThrownBy(() -> couponService.issue("WELCOME5000", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이미 발급된 쿠폰");
    }

    @Test
    void issue_coupon_not_found() {
        assertThatThrownBy(() -> couponService.issue("NONEXISTENT", 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issue_out_of_stock() {
        Coupon limitedCoupon = couponRepository.save(Coupon.builder()
                .code("LIMITED1")
                .discountAmount(1000)
                .totalQty(1)
                .build());

        given(valueOps.decrement(anyString())).willReturn(-1L); // 재고 소진

        assertThatThrownBy(() -> couponService.issue("LIMITED1", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("재고 없음");
    }

    @Test
    void issue_redis_decr_rollback_on_stockout() {
        given(valueOps.decrement(anyString())).willReturn(-1L);

        assertThatThrownBy(() -> couponService.issue("WELCOME5000", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("재고 없음");

        // DECR 후 음수 → INCR로 복구 호출 확인
        verify(valueOps).increment(CouponService.stockKey(coupon.getId()));
    }
}
