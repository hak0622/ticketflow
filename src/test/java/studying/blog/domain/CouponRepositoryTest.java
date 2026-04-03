package studying.blog.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.repository.CouponRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CouponRepositoryTest {

    @Autowired
    private CouponRepository couponRepository;

    @Test
    void save_and_findByCode() {
        Coupon coupon = Coupon.builder()
                .code("SUMMER10")
                .discountAmount(1000)
                .totalQty(50)
                .build();
        couponRepository.save(coupon);

        Optional<Coupon> found = couponRepository.findByCode("SUMMER10");

        assertThat(found).isPresent();
        assertThat(found.get().getDiscountAmount()).isEqualTo(1000);
        assertThat(found.get().getTotalQty()).isEqualTo(50);
        assertThat(found.get().getIssuedCount()).isEqualTo(0);
    }

    @Test
    void findByCode_notFound() {
        Optional<Coupon> found = couponRepository.findByCode("NONEXISTENT");

        assertThat(found).isEmpty();
    }
}
