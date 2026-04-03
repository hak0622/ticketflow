package studying.blog.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import studying.blog.config.CustomPrincipal;
import studying.blog.domain.CouponIssue;
import studying.blog.service.CouponService;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CouponApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private CouponService couponService;

    private Authentication auth;

    @BeforeEach
    void setUp() {
        auth = new UsernamePasswordAuthenticationToken(
                new CustomPrincipal(1L, "test@test.com"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    @Test
    void issue_returns_200() throws Exception {
        // CouponIssue를 mock으로 생성해 null Id 문제 회피
        studying.blog.domain.Coupon coupon = mock(studying.blog.domain.Coupon.class);
        when(coupon.getCode()).thenReturn("WELCOME5000");
        when(coupon.getDiscountAmount()).thenReturn(5000);

        CouponIssue issue = mock(CouponIssue.class);
        when(issue.getId()).thenReturn(1L);
        when(issue.getCoupon()).thenReturn(coupon);
        when(issue.getIssuedAt()).thenReturn(java.time.LocalDateTime.now());

        given(couponService.issue("WELCOME5000", 1L)).willReturn(issue);

        mockMvc.perform(post("/api/coupons/WELCOME5000/issue")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponCode").value("WELCOME5000"))
                .andExpect(jsonPath("$.discountAmount").value(5000))
                .andExpect(jsonPath("$.couponIssueId").value(1));
    }

    @Test
    void issue_out_of_stock_returns_409() throws Exception {
        willThrow(new IllegalStateException("재고 없음"))
                .given(couponService).issue("WELCOME5000", 1L);

        mockMvc.perform(post("/api/coupons/WELCOME5000/issue")
                        .with(authentication(auth)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("재고 없음"));
    }
}
